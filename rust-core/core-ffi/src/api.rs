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

        {
            let mut state = lock(&self.shared.inner);
            state.last_frame_at = Some(Instant::now());
        }
        self.shared
            .counters
            .frames_in
            .fetch_add(1, Ordering::Relaxed);

        // Hold the detector lock across the sampling check, inference, and the
        // resulting state update. Two callers therefore cannot interleave
        // inference results out of order. The guard is dropped before listeners
        // run, so a slow listener cannot stall the next frame.
        let mut detector_guard = lock(&self.detector);
        let Some(detector) = detector_guard.as_mut() else {
            return Err(EngineError::NotRunning);
        };

        let due = {
            let state = lock(&self.shared.inner);
            state
                .last_inference_at
                .is_none_or(|last| last.elapsed() >= state.inference_interval)
        };
        if !due {
            // A throttled frame proves the camera is alive, but it is not
            // evidence that a broken model recovered. The check and the
            // transition share one lock so a concurrent inference failure
            // cannot be overwritten by a frame that was already in flight.
            let changed = self.promote_connected_if_model_ready(now);
            drop(detector_guard);
            if changed && self.running.load(Ordering::Acquire) {
                self.publish_stream(StreamStatus::Connected);
            }
            return Ok(Vec::new());
        }

        // Count the attempt, not just the success. Otherwise a broken model
        // would retry on every incoming frame (30+ times per second) instead of
        // at the configured inference rate.
        {
            let mut state = lock(&self.shared.inner);
            state.last_inference_at = Some(Instant::now());
        }

        let detections = match detector.detect(rgb_frame) {
            Ok(detections) => detections,
            Err(error) => {
                drop(detector_guard);
                self.degrade_after_inference_failure();
                return Err(error.into());
            }
        };
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

        let (stream_recovered, events) = {
            let mut state = lock(&self.shared.inner);
            if !self.running.load(Ordering::Acquire) {
                return Ok(Vec::new());
            }
            // A successful inference is a real observation: mark the model
            // healthy and revive the spatial state machine before feeding it,
            // otherwise the tables would ignore this frame and stay `unknown`
            // forever. Both happen under one lock so a watchdog timeout cannot
            // land between the recovery and the observation.
            let stream_recovered = Self::mark_frame_healthy_locked(&self.shared, &mut state, now);
            let events = state.observe_frame(&pixel_boxes, frame, now);
            state.apply_events(&events);
            (stream_recovered, events)
        };

        drop(detector_guard);
        if stream_recovered && self.running.load(Ordering::Acquire) {
            self.publish_stream(StreamStatus::Connected);
        }
        if self.running.load(Ordering::Acquire) {
            self.publish_tables(&events);
        }
        Ok(events)
    }

    /// Mark the detector healthy and revive the stream state machines.
    ///
    /// Idempotent: only the first caller after a failure publishes `connected`
    /// and increments the connect counter.
    #[cfg(test)]
    fn mark_frame_healthy(&self, now: Duration) -> bool {
        let changed = self.mark_frame_healthy_state(now);
        if changed {
            self.publish_stream(StreamStatus::Connected);
        }
        changed
    }

    /// Mutate stream/model state only; callers publish after dropping locks.
    #[cfg(test)]
    fn mark_frame_healthy_state(&self, now: Duration) -> bool {
        let mut state = lock(&self.shared.inner);
        Self::mark_frame_healthy_locked(&self.shared, &mut state, now)
    }

    /// Healthy transition with the state lock already held.
    fn mark_frame_healthy_locked(
        shared: &SharedState,
        state: &mut StateInner,
        now: Duration,
    ) -> bool {
        let model_recovered = !state.model_ready;
        state.model_ready = true;
        let changed = Self::promote_connected(shared, state, now);
        // A previous failure may have frozen the state machines while the
        // stream status was already `connected`. Revive them even when no
        // stream event needs publishing, or the next observation would be
        // ignored forever.
        if model_recovered && !changed {
            state.unfreeze_stream(now);
        }
        changed
    }

    /// Promote `connected` for a live frame only while the model is healthy.
    fn promote_connected_if_model_ready(&self, now: Duration) -> bool {
        let mut state = lock(&self.shared.inner);
        // `stop` clears `running` before it takes the state lock, so checking
        // it here guarantees a frame that finishes after `stop` cannot flip the
        // engine back to `connected`.
        self.running.load(Ordering::Acquire)
            && state.model_ready
            && Self::promote_connected(&self.shared, &mut state, now)
    }

    /// Move the stream to `connected` and revive the spatial state machines.
    ///
    /// Returns `true` only when the status actually changed, so callers publish
    /// one event instead of one per frame.
    fn promote_connected(shared: &SharedState, state: &mut StateInner, now: Duration) -> bool {
        if state.stream == StreamStatus::Connected {
            return false;
        }
        state.stream = StreamStatus::Connected;
        state.unfreeze_stream(now);
        shared.counters.connects.fetch_add(1, Ordering::Relaxed);
        shared
            .counters
            .reconnect_failures
            .store(0, Ordering::Relaxed);
        true
    }

    /// Invalidate every occupancy reading after the model failed this frame.
    fn degrade_after_inference_failure(&self) {
        let outcome = {
            let mut state = lock(&self.shared.inner);
            if self.running.load(Ordering::Acquire) {
                let report_status = state.stream != StreamStatus::Error;
                let now = state
                    .started_at
                    .map_or(Duration::ZERO, |started| started.elapsed());
                let events = state.mark_inference_failure(StreamStatus::Error, now);
                state.apply_events(&events);
                Some((events, report_status))
            } else {
                None
            }
        };
        let Some((events, report_status)) = outcome else {
            return;
        };
        if report_status {
            self.publish_stream(StreamStatus::Error);
        }
        if !events.is_empty() {
            self.publish_tables(&events);
        }
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
    fn every_frame_after_a_model_error_still_reports_unknown() {
        let engine = Engine::new();
        let recorder = Arc::new(RecordingListener::default());
        engine.subscribe(Arc::clone(&recorder) as Arc<dyn EngineListener>);
        engine.running.store(true, Ordering::Release);
        {
            let mut state = lock(&engine.shared.inner);
            state
                .install_tables(
                    ONE_TABLE,
                    spatial_engine::DebounceConfig::new(Duration::ZERO),
                    Duration::ZERO,
                )
                .unwrap();
            state.model_ready = true;
            state.stream = StreamStatus::Connected;
            state.unfreeze_stream(Duration::ZERO);
        }

        engine.degrade_after_inference_failure();
        engine.degrade_after_inference_failure();
        let health = engine.health().unwrap();

        assert_eq!(health.stream, StreamStatus::Error);
        assert!(!health.model_ready);
        assert_eq!(health.tables_unknown, 1);
        let events = lock(&recorder.events);
        let stream_errors = events
            .iter()
            .filter(|event| {
                matches!(
                    event,
                    EngineEvent::Stream {
                        status: StreamStatus::Error
                    }
                )
            })
            .count();
        assert_eq!(stream_errors, 1, "repeated failures must not spam the UI");
    }

    #[test]
    fn a_healthy_frame_publishes_connected_once() {
        let engine = Engine::new();
        let recorder = Arc::new(RecordingListener::default());
        engine.subscribe(Arc::clone(&recorder) as Arc<dyn EngineListener>);
        {
            let mut state = lock(&engine.shared.inner);
            state
                .install_tables(
                    ONE_TABLE,
                    spatial_engine::DebounceConfig::new(Duration::ZERO),
                    Duration::ZERO,
                )
                .unwrap();
        }

        engine.mark_frame_healthy(Duration::ZERO);
        engine.mark_frame_healthy(Duration::from_millis(10));

        let events = lock(&recorder.events);
        assert_eq!(
            events.as_slice(),
            &[EngineEvent::Stream {
                status: StreamStatus::Connected
            }]
        );
    }

    #[test]
    fn inference_failure_marks_tables_unknown_and_recovery_publishes_connected() {
        let engine = Engine::new();
        let recorder = Arc::new(RecordingListener::default());
        engine.subscribe(Arc::clone(&recorder) as Arc<dyn EngineListener>);
        engine.running.store(true, Ordering::Release);
        {
            let mut state = lock(&engine.shared.inner);
            state
                .install_tables(
                    ONE_TABLE,
                    spatial_engine::DebounceConfig::new(Duration::ZERO),
                    Duration::ZERO,
                )
                .unwrap();
            state.model_ready = true;
            state.stream = StreamStatus::Connected;
            state.unfreeze_stream(Duration::ZERO);
        }

        engine.mark_frame_healthy(Duration::ZERO);
        engine.degrade_after_inference_failure();

        let health = engine.health().unwrap();
        assert_eq!(health.stream, StreamStatus::Error);
        assert!(!health.model_ready);
        assert_eq!(health.tables_unknown, 1);

        engine.mark_frame_healthy(Duration::from_millis(20));
        let health = engine.health().unwrap();
        assert_eq!(health.stream, StreamStatus::Connected);
        assert_eq!(health.tables_unknown, 1, "no observation happened yet");
        assert!(health.model_ready);
    }

    #[test]
    fn recovery_revives_the_spatial_state_machine_for_the_next_observation() {
        let engine = Engine::new();
        engine.running.store(true, Ordering::Release);
        {
            let mut state = lock(&engine.shared.inner);
            state
                .install_tables(
                    ONE_TABLE,
                    spatial_engine::DebounceConfig::new(Duration::ZERO),
                    Duration::ZERO,
                )
                .unwrap();
            state.model_ready = true;
            state.stream = StreamStatus::Connected;
            state.unfreeze_stream(Duration::ZERO);
        }
        engine.degrade_after_inference_failure();
        assert_eq!(engine.health().unwrap().tables_unknown, 1);

        engine.mark_frame_healthy(Duration::from_millis(20));
        let frame = FrameSize::new(1280, 720).unwrap();
        let events = {
            let mut state = lock(&engine.shared.inner);
            let events = state.observe_frame(&[], frame, Duration::from_millis(30));
            state.apply_events(&events);
            events
        };

        assert_eq!(events.len(), 1);
        assert_eq!(events[0].status, crate::types::TableStatus::Available);
        assert_eq!(engine.health().unwrap().tables_available, 1);
    }

    #[test]
    fn a_throttled_frame_cannot_revive_a_model_that_just_failed() {
        let engine = Engine::new();
        {
            let mut state = lock(&engine.shared.inner);
            state
                .install_tables(
                    ONE_TABLE,
                    spatial_engine::DebounceConfig::new(Duration::ZERO),
                    Duration::ZERO,
                )
                .unwrap();
            state.model_ready = false;
            state.stream = StreamStatus::Error;
        }

        // The model is unhealthy, so a live-but-throttled frame must keep the
        // stream in `error` instead of flipping it back to `connected`.
        let changed = engine.promote_connected_if_model_ready(Duration::from_millis(10));
        assert!(!changed);

        let health = engine.health().unwrap();
        assert_eq!(health.stream, StreamStatus::Error);
        assert!(!health.model_ready);
    }

    #[test]
    fn a_frame_that_finishes_after_stop_cannot_revive_connected() {
        let engine = Engine::new();
        {
            let mut state = lock(&engine.shared.inner);
            state
                .install_tables(
                    ONE_TABLE,
                    spatial_engine::DebounceConfig::new(Duration::ZERO),
                    Duration::ZERO,
                )
                .unwrap();
            state.model_ready = true;
            state.stream = StreamStatus::Error;
        }
        engine.running.store(false, Ordering::Release);

        let changed = engine.promote_connected_if_model_ready(Duration::from_millis(10));

        assert!(!changed);
        assert_eq!(engine.health().unwrap().stream, StreamStatus::Error);
    }

    #[test]
    fn recovery_revives_tables_even_if_the_stream_never_left_connected() {
        let engine = Engine::new();
        {
            let mut state = lock(&engine.shared.inner);
            state
                .install_tables(
                    ONE_TABLE,
                    spatial_engine::DebounceConfig::new(Duration::ZERO),
                    Duration::ZERO,
                )
                .unwrap();
            state.model_ready = true;
            state.stream = StreamStatus::Connected;
            state.unfreeze_stream(Duration::ZERO);
        }
        {
            // Simulate a model failure that did not change the stream status.
            let mut state = lock(&engine.shared.inner);
            state.model_ready = false;
            let events = state.mark_stream_down(StreamStatus::Error, Duration::ZERO);
            state.apply_events(&events);
            state.stream = StreamStatus::Connected;
        }
        assert_eq!(engine.health().unwrap().tables_unknown, 1);

        engine.mark_frame_healthy(Duration::from_millis(20));

        let frame = FrameSize::new(1280, 720).unwrap();
        let events = {
            let mut state = lock(&engine.shared.inner);
            let events = state.observe_frame(&[], frame, Duration::from_millis(30));
            state.apply_events(&events);
            events
        };
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].status, crate::types::TableStatus::Available);
    }

    #[test]
    fn a_failed_start_can_be_retried_instead_of_looking_running() {
        let engine = Engine::new();
        assert!(engine.start(config("model.onnx", ONE_TABLE)).is_err());
        let second = engine.start(config("model.onnx", ONE_TABLE)).unwrap_err();
        assert!(matches!(second, EngineError::Model { .. }));
    }
}
