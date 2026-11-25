//! The object Kotlin actually calls.
//!
//! Locking rules:
//! - `lifecycle` serialises `start`/`stop`; `push_frame` never holds it.
//! - No listener is ever invoked while [`StateInner`] or a detector is locked.
//!   Events are collected under the lock, guards are dropped, then listeners
//!   run, so a slow listener cannot stall inference or shutdown.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::{Duration, Instant};

use ai_engine::{Detector, DetectorConfig, RgbFrame};
use spatial_engine::{DebounceConfig, Detection, FrameSize, PixelBox, TableConfigSet};

use crate::error::EngineError;
use crate::panic_guard::guard;
use crate::runtime::{Watchdog, FRAME_STALE_AFTER};
use crate::state::{lock, millis, SharedState, StateInner};
use crate::types::{EngineConfig, EngineEvent, EngineHealth, StreamStatus, TableEvent};

const MIN_INFERENCE_FPS: f64 = 0.5;
const MAX_INFERENCE_FPS: f64 = 10.0;
const MAX_DEBOUNCE_MS: u64 = 60_000;

/// Receives engine events. Implemented on the Kotlin side.
///
/// Calls arrive on the thread that called [`Engine::push_frame`],
/// `start`, `stop`, or the watchdog thread. A listener must not call back into
/// the engine synchronously.
#[uniffi::export(foreign)]
pub trait EngineListener: Send + Sync {
    /// One event, in the order it happened.
    fn on_event(&self, event: EngineEvent);
}

/// Occupancy engine exposed to Kotlin.
#[derive(uniffi::Object)]
pub struct Engine {
    shared: SharedState,
    detector: Mutex<Option<Detector>>,
    watchdog: Mutex<Option<Watchdog>>,
    listeners: Mutex<Vec<Arc<dyn EngineListener>>>,
    lifecycle: Mutex<()>,
    running: Arc<AtomicBool>,
    self_reference: OnceLock<Weak<Engine>>,
}

#[uniffi::export]
impl Engine {
    /// Create a stopped engine.
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        let engine = Arc::new(Self {
            shared: SharedState::default(),
            detector: Mutex::new(None),
            watchdog: Mutex::new(None),
            listeners: Mutex::new(Vec::new()),
            lifecycle: Mutex::new(()),
            running: Arc::new(AtomicBool::new(false)),
            self_reference: OnceLock::new(),
        });
        let _ = engine.self_reference.set(Arc::downgrade(&engine));
        engine
    }

    /// Load ONNX Runtime and the model, then accept frames.
    ///
    /// Fails with [`EngineError::AlreadyRunning`] if the engine is already
    /// running. A failed start leaves the engine stopped.
    pub fn start(&self, config: EngineConfig) -> Result<(), EngineError> {
        guard(|| self.start_inner(config))
    }

    /// Stop the watchdog and release the model. Safe to call when stopped.
    pub fn stop(&self) -> Result<(), EngineError> {
        guard(|| self.stop_inner())
    }

    /// Replace the table configuration while running or stopped.
    ///
    /// Every table becomes `unknown`; the next frame decides again. Rejected
    /// JSON leaves the previous configuration untouched.
    pub fn set_tables(&self, tables_json: String) -> Result<(), EngineError> {
        guard(|| {
            let now = self.session_time();
            let debounce = self.debounce();
            let running = self.running.load(Ordering::Acquire);
            {
                let mut state = lock(&self.shared.inner);
                state.install_tables(&tables_json, debounce, now)?;
                if !running {
                    state.stream = StreamStatus::Idle;
                }
            }
            if running {
                self.publish_stream(StreamStatus::Connecting);
            }
            Ok(())
        })
    }

    /// Subscribe a listener. Subscriptions survive `stop`/`start`.
    pub fn subscribe(&self, listener: Arc<dyn EngineListener>) {
        lock(&self.listeners).push(listener);
    }

    /// Remove every listener.
    pub fn clear_listeners(&self) {
        lock(&self.listeners).clear();
    }

    /// Analyse one RGB frame.
    ///
    /// `rgb` must be packed RGB888 with `width * height * 3` bytes. Frames
    /// arriving faster than the configured inference rate are counted and
    /// still drive stream health, but only sampled frames run inference.
    pub fn push_frame(
        &self,
        width: u32,
        height: u32,
        rgb: Vec<u8>,
    ) -> Result<Vec<TableEvent>, EngineError> {
        guard(|| self.push_frame_inner(width, height, &rgb))
    }

    /// Tell the engine that the caller's RTSP connection failed.
    ///
    /// Kotlin owns the preview connection; this keeps health and table status
    /// honest when the camera drops without a frame ever arriving.
    pub fn report_stream_error(&self, status: StreamStatus) -> Result<(), EngineError> {
        guard(|| {
            if matches!(status, StreamStatus::Connected | StreamStatus::Idle) {
                return Err(EngineError::Config {
                    reason: "report_stream_error expects a failure status".to_string(),
                });
            }
            if !self.running.load(Ordering::Acquire) {
                return Err(EngineError::NotRunning);
            }

            let events = {
                let mut state = lock(&self.shared.inner);
                self.shared
                    .counters
                    .reconnect_failures
                    .fetch_add(1, Ordering::Relaxed);
                let now = state
                    .started_at
                    .map_or(Duration::ZERO, |started| started.elapsed());
                let events = state.mark_stream_down(status, now);
                state.apply_events(&events);
                events
            };
            self.publish_stream(status);
            self.publish_tables(&events);
            Ok(())
        })
    }

    /// Current health snapshot.
    pub fn health(&self) -> Result<EngineHealth, EngineError> {
        guard(|| self.health_inner())
    }
}

impl Engine {
    fn start_inner(&self, config: EngineConfig) -> Result<(), EngineError> {
        let _lifecycle = lock(&self.lifecycle);
        if self.running.load(Ordering::Acquire) {
            return Err(EngineError::AlreadyRunning);
        }

        match self.start_session(config) {
            Ok(()) => {
                self.running.store(true, Ordering::Release);
                self.publish_stream(StreamStatus::Connecting);
                Ok(())
            }
            Err(error) => {
                let _ = lock(&self.watchdog).take();
                lock(&self.detector).take();
                *lock(&self.shared.inner) = StateInner::default();
                self.shared.counters.reset();
                Err(error)
            }
        }
    }

    fn start_session(&self, config: EngineConfig) -> Result<(), EngineError> {
        if config.model_path.trim().is_empty() {
            return Err(EngineError::Config {
                reason: "model path must not be empty".to_string(),
            });
        }

        let tables = TableConfigSet::from_json(&config.tables_json)?;
        if tables.is_empty() {
            return Err(EngineError::Config {
                reason: "at least one table is required".to_string(),
            });
        }

        ai_engine::configure_library_path(&config.onnx_runtime_path)?;

        let detector = Detector::from_file(&config.model_path, DetectorConfig::default())?;
        let debounce = DebounceConfig::new(Duration::from_millis(
            config.debounce_ms.min(MAX_DEBOUNCE_MS),
        ));
        let inference_fps = if config.inference_fps.is_finite() {
            config
                .inference_fps
                .clamp(MIN_INFERENCE_FPS, MAX_INFERENCE_FPS)
        } else {
            MIN_INFERENCE_FPS
        };
        let now = Instant::now();

        {
            let mut state = lock(&self.shared.inner);
            *state = StateInner::default();
            state.begin_session(now);
            state.model_ready = true;
            state.inference_interval = Duration::from_secs_f64(1.0 / inference_fps);
            state.install_tables(&config.tables_json, debounce, Duration::ZERO)?;
            self.shared.counters.reset();
        }
        *lock(&self.detector) = Some(detector);

        let on_stale = self.stale_callback();
        let watchdog = Watchdog::spawn(self.shared.clone(), Arc::clone(&self.running), on_stale)?;
        *lock(&self.watchdog) = Some(watchdog);
        Ok(())
    }

    fn stale_callback(&self) -> Arc<dyn Fn(StreamStatus, Vec<TableEvent>) + Send + Sync> {
        let weak = self.self_reference.get().cloned().unwrap_or_default();
        Arc::new(move |status: StreamStatus, events: Vec<TableEvent>| {
            if let Some(engine) = weak.upgrade() {
                engine.publish_stream(status);
                engine.publish_tables(&events);
            }
        })
    }

    fn stop_inner(&self) -> Result<(), EngineError> {
        let _lifecycle = lock(&self.lifecycle);
        if !self.running.swap(false, Ordering::AcqRel) {
            return Ok(());
        }

        let stop_result = lock(&self.watchdog)
            .take()
            .map_or(Ok(()), |mut watchdog| watchdog.stop());

        lock(&self.detector).take();

        let events = {
            let mut state = lock(&self.shared.inner);
            state.model_ready = false;
            let now = state
                .started_at
                .map_or(Duration::ZERO, |started| started.elapsed());
            let events = state.mark_stream_down(StreamStatus::Idle, now);
            state.apply_events(&events);
            events
        };

        self.publish_stream(StreamStatus::Idle);
        self.publish_tables(&events);
        stop_result
    }

    fn push_frame_inner(
        &self,
        width: u32,
        height: u32,
        rgb: &[u8],
    ) -> Result<Vec<TableEvent>, EngineError> {
        if !self.running.load(Ordering::Acquire) {
            return Err(EngineError::NotRunning);
        }

        let frame = FrameSize::new(width, height).ok_or_else(|| EngineError::Frame {
            reason: "width and height must be non-zero".to_string(),
        })?;
        let rgb_frame = RgbFrame::new(width, height, rgb)?;
        let now = self.session_time();

        let status_change = {
            let mut state = lock(&self.shared.inner);
            let status_change = if state.stream == StreamStatus::Connected {
                None
            } else {
                state.stream = StreamStatus::Connected;
                state.unfreeze_stream(now);
                self.shared
                    .counters
                    .connects
                    .fetch_add(1, Ordering::Relaxed);
                self.shared
                    .counters
                    .reconnect_failures
                    .store(0, Ordering::Relaxed);
                Some(StreamStatus::Connected)
            };
            state.last_frame_at = Some(Instant::now());
            status_change
        };
        self.shared
            .counters
            .frames_in
            .fetch_add(1, Ordering::Relaxed);

        if let Some(status) = status_change {
            self.publish_stream(status);
        }

        // Hold the detector lock across the sampling check and inference so two
        // callers can never run inference concurrently.
        let mut detector = lock(&self.detector);
        let Some(detector) = detector.as_mut() else {
            return Err(EngineError::NotRunning);
        };

        let due = {
            let state = lock(&self.shared.inner);
            state
                .last_inference_at
                .is_none_or(|last| last.elapsed() >= state.inference_interval)
        };
        if !due {
            return Ok(Vec::new());
        }

        let detections = detector.detect(rgb_frame)?;
        if !self.running.load(Ordering::Acquire) {
            return Ok(Vec::new());
        }

        self.shared
            .counters
            .frames_sampled
            .fetch_add(1, Ordering::Relaxed);

        let pixel_boxes: Vec<Detection> = detections
            .iter()
            .map(|bbox| {
                Detection::new(
                    PixelBox::new(
                        f64::from(bbox.x),
                        f64::from(bbox.y),
                        f64::from(bbox.width),
                        f64::from(bbox.height),
                    ),
                    f64::from(bbox.confidence),
                )
            })
            .collect();

        let events = {
            let mut state = lock(&self.shared.inner);
            state.last_inference_at = Some(Instant::now());
            let events = state.observe_frame(&pixel_boxes, frame, now);
            state.apply_events(&events);
            events
        };

        self.publish_tables(&events);
        Ok(events)
    }

    fn health_inner(&self) -> Result<EngineHealth, EngineError> {
        let state = lock(&self.shared.inner);
        let (available, occupied, unknown) = state.aggregate();
        let uptime = state
            .started_at
            .map_or(Duration::ZERO, |started| started.elapsed());

        Ok(EngineHealth {
            stream: state.stream,
            frames_in: self.shared.counters.frames_in.load(Ordering::Relaxed),
            frames_sampled: self.shared.counters.frames_sampled.load(Ordering::Relaxed),
            connects: self.shared.counters.connects.load(Ordering::Relaxed),
            reconnect_failures: self
                .shared
                .counters
                .reconnect_failures
                .load(Ordering::Relaxed),
            last_frame_age_ms: state.last_frame_at.map_or(0, |last| millis(last.elapsed())),
            tables: u32::try_from(state.tables.len()).unwrap_or(u32::MAX),
            tables_available: available,
            tables_occupied: occupied,
            tables_unknown: unknown,
            uptime_ms: millis(uptime),
            needs_recalibration: state.needs_recalibration,
            model_ready: state.model_ready,
        })
    }

    fn session_time(&self) -> Duration {
        lock(&self.shared.inner)
            .started_at
            .map_or(Duration::ZERO, |started| started.elapsed())
    }

    fn debounce(&self) -> DebounceConfig {
        lock(&self.shared.inner)
            .spatial
            .as_ref()
            .map_or(DebounceConfig::default_hold(), |spatial| spatial.debounce())
    }

    fn publish_stream(&self, status: StreamStatus) {
        self.dispatch(&EngineEvent::Stream { status });
    }

    fn publish_tables(&self, events: &[TableEvent]) {
        for event in events {
            self.dispatch(&EngineEvent::Table {
                event: event.clone(),
            });
        }
    }

    fn dispatch(&self, event: &EngineEvent) {
        let listeners: Vec<Arc<dyn EngineListener>> = lock(&self.listeners).clone();
        for listener in listeners {
            // A listener is caller code running across the FFI boundary. A
            // panic there must not poison the engine or skip later listeners.
            if std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                listener.on_event(event.clone());
            }))
            .is_err()
            {
                eprintln!("cctv-stream: listener panicked; event dropped");
            }
        }
    }
}

/// Frame-age threshold exposed to Kotlin for its own UI copy.
#[uniffi::export]
pub fn frame_stale_after_ms() -> u64 {
    millis(FRAME_STALE_AFTER)
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use super::*;

    const ONE_TABLE: &str = r#"{
        "version": 2,
        "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
        "tables": [
            { "id": "a", "polygon": [
                { "x": 0.1, "y": 0.1 },
                { "x": 0.3, "y": 0.1 },
                { "x": 0.3, "y": 0.3 }
            ] }
        ]
    }"#;

    struct PanickingListener;

    impl EngineListener for PanickingListener {
        fn on_event(&self, _event: EngineEvent) {
            panic!("listener exploded");
        }
    }

    #[derive(Default)]
    struct RecordingListener {
        events: Mutex<Vec<EngineEvent>>,
    }

    impl EngineListener for RecordingListener {
        fn on_event(&self, event: EngineEvent) {
            lock(&self.events).push(event);
        }
    }

    fn config(model_path: &str, tables_json: &str) -> EngineConfig {
        EngineConfig {
            model_path: model_path.to_string(),
            onnx_runtime_path: "no-such-onnxruntime.so".to_string(),
            tables_json: tables_json.to_string(),
            inference_fps: 2.0,
            debounce_ms: 1_000,
        }
    }

    #[test]
    fn push_frame_before_start_is_rejected() {
        let engine = Engine::new();
        let error = engine.push_frame(1, 1, vec![0; 3]).unwrap_err();
        assert_eq!(error, EngineError::NotRunning);
    }

    #[test]
    fn stop_before_start_is_ok() {
        let engine = Engine::new();
        assert_eq!(engine.stop(), Ok(()));
    }

    #[test]
    fn invalid_tables_are_rejected() {
        let engine = Engine::new();
        let error = engine.set_tables("{not json".to_string()).unwrap_err();
        assert!(matches!(error, EngineError::Tables { .. }));
    }

    #[test]
    fn report_stream_error_requires_a_failure_status() {
        let engine = Engine::new();
        assert!(engine.report_stream_error(StreamStatus::Connected).is_err());
    }

    #[test]
    fn report_stream_error_before_start_is_rejected() {
        let engine = Engine::new();
        assert_eq!(
            engine.report_stream_error(StreamStatus::Timeout),
            Err(EngineError::NotRunning)
        );
    }

    #[test]
    fn a_panicking_listener_does_not_skip_later_listeners() {
        let engine = Engine::new();
        engine.subscribe(Arc::new(PanickingListener));
        let recorder = Arc::new(RecordingListener::default());
        engine.subscribe(Arc::clone(&recorder) as Arc<dyn EngineListener>);

        engine.publish_stream(StreamStatus::Connecting);

        let events = lock(&recorder.events);
        assert_eq!(
            events.as_slice(),
            &[EngineEvent::Stream {
                status: StreamStatus::Connecting
            }]
        );
    }

    #[test]
    fn engine_stays_usable_after_a_listener_panics() {
        let engine = Engine::new();
        engine.subscribe(Arc::new(PanickingListener));
        let recorder = Arc::new(RecordingListener::default());
        engine.subscribe(Arc::clone(&recorder) as Arc<dyn EngineListener>);

        engine.publish_stream(StreamStatus::Connecting);
        engine.publish_stream(StreamStatus::Connected);

        let events = lock(&recorder.events);
        assert_eq!(events.len(), 2);
        assert_eq!(
            events[1],
            EngineEvent::Stream {
                status: StreamStatus::Connected
            }
        );
        assert_eq!(engine.stop(), Ok(()));
    }

    #[test]
    fn start_rejects_an_empty_model_path() {
        let engine = Engine::new();
        let error = engine.start(config("   ", ONE_TABLE)).unwrap_err();
        assert!(matches!(error, EngineError::Config { .. }));
        assert_eq!(
            engine.push_frame(1, 1, vec![0; 3]).unwrap_err(),
            EngineError::NotRunning
        );
    }

    #[test]
    fn start_rejects_an_empty_table_set() {
        let tables = r#"{
            "version": 2,
            "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
            "tables": []
        }"#;
        let engine = Engine::new();
        let error = engine.start(config("model.onnx", tables)).unwrap_err();
        assert!(matches!(error, EngineError::Config { .. }));
    }

    #[test]
    fn start_rejects_a_missing_onnx_runtime_library() {
        let engine = Engine::new();
        let error = engine.start(config("model.onnx", ONE_TABLE)).unwrap_err();
        assert!(matches!(error, EngineError::Model { .. }));
        assert_eq!(
            engine.push_frame(1, 1, vec![0; 3]).unwrap_err(),
            EngineError::NotRunning
        );
    }

    #[test]
    fn a_failed_start_can_be_retried_instead_of_looking_running() {
        let engine = Engine::new();
        assert!(engine.start(config("model.onnx", ONE_TABLE)).is_err());
        let second = engine.start(config("model.onnx", ONE_TABLE)).unwrap_err();
        assert!(matches!(second, EngineError::Model { .. }));
    }
}
