//! Engine state shared between FFI calls and the watchdog.

use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use spatial_engine::{
    DebounceConfig, Detection, FrameSize, NormalizedDetection, SpatialEngine, TableConfigSet,
    ViewGuard, ViewSignature, ViewVerdict,
};

use crate::error::EngineError;
use crate::types::{DetectionBox, StreamStatus, TableEvent, TableStatus};

/// Snapshot of table plus per-table bookkeeping.
#[derive(Debug, Clone)]
pub(crate) struct TableSnapshot {
    pub(crate) label: String,
    pub(crate) status: TableStatus,
    pub(crate) persons: u32,
    pub(crate) confidence: f32,
    pub(crate) since_ms: u64,
}

/// Counters that survive a stop/start cycle in the same process.
#[derive(Debug, Default)]
pub(crate) struct Counters {
    pub(crate) frames_in: AtomicU64,
    pub(crate) frames_sampled: AtomicU64,
    /// Frames that arrived while the detector was busy and were not analysed.
    pub(crate) frames_dropped: AtomicU64,
    pub(crate) connects: AtomicU32,
    pub(crate) reconnect_failures: AtomicU32,
}

impl Counters {
    pub(crate) fn reset(&self) {
        self.frames_in.store(0, Ordering::Relaxed);
        self.frames_sampled.store(0, Ordering::Relaxed);
        self.frames_dropped.store(0, Ordering::Relaxed);
        self.connects.store(0, Ordering::Relaxed);
        self.reconnect_failures.store(0, Ordering::Relaxed);
    }
}

/// Resident set size of this process in kibibytes, read from `/proc/self/status`.
///
/// Rust owns the inference worker and the detector session, so RSS is the only
/// honest way to tell a native leak from a Compose leak during a soak. Android
/// keeps `/proc` readable for the process itself. `VmRSS` is already reported
/// in kB, so this stays correct on devices with 16 KiB pages. Any other
/// platform or a missing line reports `None` instead of inventing a number.
pub(crate) fn resident_set_kb() -> Option<u64> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    status.lines().find_map(|line| {
        let rest = line.strip_prefix("VmRSS:")?;
        rest.split_whitespace().next()?.parse().ok()
    })
}

/// One analysed person box plus the frame it was measured on.
///
/// Deliberately not the FFI record: the engine keeps a monotonic [`Duration`]
/// stamp, and only [`AnalysedBox::to_payload`] turns it into the millisecond
/// numbers Kotlin sees. That conversion runs when the boxes are read, so
/// `age_ms` is measured against the current session clock and not against a
/// health snapshot the caller may have cached.
#[derive(Debug, Clone)]
pub(crate) struct AnalysedBox {
    pub(crate) x: f32,
    pub(crate) y: f32,
    pub(crate) width: f32,
    pub(crate) height: f32,
    pub(crate) confidence: f32,
    /// Analysed frame this box came from (`1` for the first of a session).
    pub(crate) frame_seq: u64,
    /// Session time when that frame reached the engine.
    pub(crate) captured_at: Duration,
}

impl AnalysedBox {
    /// Build the FFI record, deriving the age from the session clock.
    fn to_payload(&self, now_ms: u64) -> DetectionBox {
        let captured_at_ms = millis(self.captured_at);
        DetectionBox {
            x: self.x,
            y: self.y,
            width: self.width,
            height: self.height,
            confidence: self.confidence,
            frame_seq: self.frame_seq,
            captured_at_ms,
            age_ms: now_ms.saturating_sub(captured_at_ms),
        }
    }
}

/// Mutable state guarded by one lock.
#[derive(Debug)]
pub(crate) struct StateInner {
    /// Camera that owns the current (or most recent) session.
    pub(crate) camera_id: String,
    pub(crate) stream: StreamStatus,
    pub(crate) needs_recalibration: bool,
    pub(crate) model_ready: bool,
    pub(crate) config: Option<TableConfigSet>,
    pub(crate) spatial: Option<SpatialEngine>,
    pub(crate) tables: BTreeMap<String, TableSnapshot>,
    /// Normalised person boxes from the last analysed frame.
    pub(crate) detections: Vec<AnalysedBox>,
    /// Analysed frames in the current session.
    ///
    /// Stamped onto every published box as `frame_seq`; it only moves when an
    /// inference result actually replaced the boxes, never when one is merely
    /// read. Only a fresh `start` (new `StateInner`) takes it back to zero.
    pub(crate) analysis_seq: u64,
    pub(crate) last_frame_at: Option<Instant>,
    pub(crate) started_at: Option<Instant>,
    pub(crate) last_inference_at: Option<Instant>,
    /// Newest frame seen when inference last started, for latency reporting.
    pub(crate) last_inference_frame_at: Option<Instant>,
    /// Durations of the most recent inference calls, oldest first.
    ///
    /// Bounded so a multi-day session cannot grow native memory; p50/p95 only
    /// need a recent window, not the whole history.
    pub(crate) inference_samples: VecDeque<Duration>,
    pub(crate) inference_interval: Duration,
    /// Detects a camera that was panned or tilted away from the view its table
    /// polygons were drawn for.
    pub(crate) view_guard: ViewGuard,
    /// Verdict for the newest analysed frame.
    pub(crate) view: ViewVerdict,
}

impl Default for StateInner {
    fn default() -> Self {
        Self {
            camera_id: String::new(),
            stream: StreamStatus::Idle,
            needs_recalibration: false,
            model_ready: false,
            config: None,
            spatial: None,
            tables: BTreeMap::new(),
            detections: Vec::new(),
            analysis_seq: 0,
            last_frame_at: None,
            started_at: None,
            last_inference_at: None,
            last_inference_frame_at: None,
            inference_samples: VecDeque::new(),
            inference_interval: Duration::from_secs_f64(1.0 / 5.0),
            view_guard: ViewGuard::default(),
            view: ViewVerdict::default(),
        }
    }
}

/// Longest inference-history window kept for p50/p95 reporting.
///
/// 120 samples cover the last minute at 2 FPS and still bound native memory
/// to a few kilobytes, so a multi-hour session cannot leak through metrics.
pub(crate) const MAX_INFERENCE_SAMPLES: usize = 120;

/// Record one inference duration, evicting the oldest sample when full.
pub(crate) fn record_inference_sample(samples: &mut VecDeque<Duration>, elapsed: Duration) {
    if samples.len() == MAX_INFERENCE_SAMPLES {
        samples.pop_front();
    }
    samples.push_back(elapsed);
}

/// Nearest-rank percentile of a sample window, in milliseconds.
///
/// Returns `0` for an empty window, which the UI reads as "not measured yet"
/// rather than a fake latency.
pub(crate) fn percentile_ms(samples: &VecDeque<Duration>, percentile: f64) -> u64 {
    if samples.is_empty() {
        return 0;
    }
    let mut sorted: Vec<Duration> = samples.iter().copied().collect();
    sorted.sort_unstable();
    let rank = (percentile / 100.0 * sorted.len() as f64).ceil() as usize;
    let index = rank.saturating_sub(1).min(sorted.len() - 1);
    millis(sorted[index])
}

impl StateInner {
    /// Reset everything for a new session, keeping only the table JSON.
    pub(crate) fn begin_session(&mut self, now: Instant) {
        self.stream = StreamStatus::Connecting;
        self.needs_recalibration = false;
        self.last_frame_at = None;
        self.last_inference_at = None;
        self.last_inference_frame_at = None;
        self.inference_samples.clear();
        self.started_at = Some(now);
        // A new session may be a different camera position: capture a fresh
        // baseline instead of comparing against the previous view.
        self.view_guard.reset();
        self.view = ViewVerdict::default();
    }

    /// Replace the table configuration.
    ///
    /// Live status is reset to `unknown` because the old polygons no longer
    /// describe the same tables. The first healthy frame calls
    /// [`StateInner::mark_stream_up`]; until then `Unknown` is the only honest
    /// answer.
    pub(crate) fn install_tables(
        &mut self,
        json: &str,
        debounce: DebounceConfig,
        now: Duration,
    ) -> Result<(), EngineError> {
        let config = TableConfigSet::from_json(json)?;
        let mut spatial = SpatialEngine::new(config.clone(), debounce, now);

        let mut tables = BTreeMap::new();
        for table in &config.tables {
            tables.insert(
                table.id.clone(),
                TableSnapshot {
                    label: table.label.clone(),
                    status: TableStatus::Unknown,
                    persons: 0,
                    confidence: 0.0,
                    since_ms: millis(now),
                },
            );
        }
        // Mark every table unknown until a frame carries a real observation.
        let _ = spatial.stream_down(now);

        self.config = Some(config);
        self.spatial = Some(spatial);
        self.tables = tables;
        self.detections.clear();
        self.stream = StreamStatus::Connecting;
        self.needs_recalibration = false;
        // The new map is drawn on whatever the camera shows now, so the next
        // frame defines the view instead of being compared against the old one.
        self.view_guard.reset();
        self.view = ViewVerdict::default();
        Ok(())
    }

    /// Unfreeze the state machines once the source is healthy.
    ///
    /// The returned `available` events are intentionally discarded: no frame
    /// has been analysed yet, so every table must stay `unknown` until
    /// inference produces a real observation. [`StateInner::observe_frame`]
    /// publishes the first real status, including `available` for an empty
    /// frame, so the UI never hangs on `unknown`.
    pub(crate) fn unfreeze_stream(&mut self, now: Duration) {
        if let Some(spatial) = self.spatial.as_mut() {
            let _ = spatial.stream_up(now);
        }
    }

    /// Apply one status transition to the snapshot map.
    pub(crate) fn apply_events(&mut self, events: &[TableEvent]) {
        for event in events {
            if let Some(table) = self.tables.get_mut(&event.table_id) {
                table.status = event.status;
                table.since_ms = event.since_ms;
                table.confidence = event.confidence;
                table.persons = event.persons_in_region;
            }
        }
    }

    /// Feed one frame signature and fold the verdict into the session.
    ///
    /// Called on every analysed frame, before [`StateInner::observe_frame`],
    /// because a moved camera invalidates the polygons this frame would be
    /// measured against. Returning from a moved view revives the debounce
    /// machines without publishing the `available` they emit: no table has been
    /// observed yet since the camera came back, so `unknown` is still the only
    /// honest status for the very first frame.
    pub(crate) fn observe_view(&mut self, signature: &ViewSignature, now: Duration) -> ViewVerdict {
        let verdict = self.view_guard.observe(signature);
        if verdict.recovered {
            if let Some(spatial) = self.spatial.as_mut() {
                let _ = spatial.stream_up(now);
            }
        }
        self.view = verdict;
        verdict
    }

    /// Report every table `unknown` while the camera view is wrong.
    ///
    /// The debounce machines are dropped along with the counts they were
    /// holding: a person counted before the camera moved says nothing about
    /// where the tables are now.
    pub(crate) fn mark_view_moved(&mut self, now: Duration) -> Vec<TableEvent> {
        self.needs_recalibration = true;
        let ids: Vec<String> = self
            .spatial
            .as_ref()
            .map(|spatial| {
                spatial
                    .table_ids()
                    .iter()
                    .map(|id| id.to_string())
                    .collect()
            })
            .unwrap_or_default();

        let Some(spatial) = self.spatial.as_mut() else {
            return Vec::new();
        };
        spatial
            .stream_down(now)
            .into_iter()
            .filter_map(|event| {
                let table = ids.get(event.table_index)?.clone();
                Some(TableEvent {
                    table_id: table,
                    status: TableStatus::Unknown,
                    since_ms: millis(event.state.since),
                    confidence: 0.0,
                    persons_in_region: 0,
                })
            })
            .collect()
    }

    /// Feed an RGB frame into the spatial engine.
    ///
    /// `detections` are pixel boxes from the model; they are converted to
    /// normalised centroids here so Kotlin never computes geometry.
    pub(crate) fn observe_frame(
        &mut self,
        detections: &[Detection],
        frame: FrameSize,
        now: Duration,
    ) -> Vec<TableEvent> {
        self.needs_recalibration = self
            .spatial
            .as_ref()
            .is_some_and(|spatial| spatial.needs_recalibration(frame));

        // The UI overlay draws model boxes, not table statuses. Converting
        // pixels to 0..1 here keeps Kotlin from re-deriving geometry and
        // guarantees the boxes and the person counts use the same frame.
        //
        // Each box also carries the analysed frame's sequence and arrival
        // time, so the overlay can hide a box that only looks fresh because
        // the poll is fast: `now` is the instant the frame reached the engine,
        // never the instant the boxes are read (Q-12 in
        // `docs/detection-quality-todo.md`).
        self.analysis_seq = self.analysis_seq.saturating_add(1);
        self.detections = detections
            .iter()
            .map(|detection| analysed_box(detection, frame, self.analysis_seq, now))
            .collect();

        // The polygons describe one camera position. Once the camera has been
        // panned or tilted, they sit on whatever happens to be behind them, so
        // this frame must not decide any table status. The boxes above are
        // still published: the people really are there, only the map is wrong.
        if self.view.moved {
            return self.mark_view_moved(now);
        }

        let normalized: Vec<NormalizedDetection> = detections
            .iter()
            .map(|detection| NormalizedDetection {
                centroid: detection.centroid(frame),
                confidence: detection.confidence,
            })
            .collect();

        // One assignment pass decides both the debounce input and the numbers
        // carried in events. Do not recompute totals per event: that turned a
        // frame into O(tables x detections) repeated work.
        let (raw, totals) = self
            .spatial
            .as_mut()
            .map(|spatial| spatial.update_normalized_with_totals(&normalized, now))
            .unwrap_or_default();

        let ids: Vec<String> = self
            .spatial
            .as_ref()
            .map(|spatial| {
                spatial
                    .table_ids()
                    .iter()
                    .map(|id| id.to_string())
                    .collect()
            })
            .unwrap_or_default();

        let mut events = Vec::with_capacity(raw.len());
        let mut emitted = BTreeSet::new();
        for event in raw {
            let Some(table_id) = ids.get(event.table_index).cloned() else {
                continue;
            };
            let (persons, confidence) = totals.get(event.table_index).map_or((0, 0.0), |totals| {
                (totals.persons, totals.confidence as f32)
            });
            emitted.insert(event.table_index);
            events.push(TableEvent {
                table_id,
                status: event.state.status.into(),
                since_ms: millis(event.state.since),
                confidence,
                persons_in_region: u32::try_from(persons).unwrap_or(u32::MAX),
            });
        }

        // `stream_up` moves the state machine out of `unknown`, but its
        // `available` event is discarded on purpose: no frame had been
        // analysed yet. The first analysed frame is a real observation, so a
        // table that is still `unknown` and has nobody in it must be published
        // as `available` now. Without this, an empty room would stay `unknown`
        // forever because an empty frame produces no debounce transition.
        //
        // The opposite case stays `unknown`: a person in the frame has not
        // passed the debounce window yet, so claiming `available` would be a
        // false reading. The state machine emits `occupied` when it confirms.
        for (index, table_id) in ids.iter().enumerate() {
            if emitted.contains(&index) {
                continue;
            }
            if self.tables.get(table_id).map(|table| table.status) != Some(TableStatus::Unknown) {
                continue;
            }
            let (persons, confidence) = totals.get(index).map_or((0, 0.0), |totals| {
                (totals.persons, totals.confidence as f32)
            });
            if persons > 0 {
                continue;
            }
            let Some(state) = self
                .spatial
                .as_ref()
                .and_then(|spatial| spatial.state(table_id))
            else {
                continue;
            };
            events.push(TableEvent {
                table_id: table_id.clone(),
                status: state.status.into(),
                since_ms: millis(state.since),
                confidence,
                persons_in_region: u32::try_from(persons).unwrap_or(u32::MAX),
            });
        }
        events
    }

    /// Mark every table unknown when the source drops or goes stale.
    pub(crate) fn mark_stream_down(
        &mut self,
        status: StreamStatus,
        now: Duration,
    ) -> Vec<TableEvent> {
        self.stream = status;
        self.last_frame_at = None;
        // A dead or stale source has no trustworthy current detections; the
        // overlay must clear instead of showing the last good frame.
        self.detections.clear();
        let ids: Vec<String> = self
            .spatial
            .as_ref()
            .map(|spatial| {
                spatial
                    .table_ids()
                    .iter()
                    .map(|id| id.to_string())
                    .collect()
            })
            .unwrap_or_default();

        let Some(spatial) = self.spatial.as_mut() else {
            return Vec::new();
        };
        spatial
            .stream_down(now)
            .into_iter()
            .filter_map(|event| {
                let table = ids.get(event.table_index)?.clone();
                Some(TableEvent {
                    table_id: table,
                    status: event.state.status.into(),
                    since_ms: millis(event.state.since),
                    confidence: 0.0,
                    persons_in_region: 0,
                })
            })
            .collect()
    }

    /// Mark the model unusable after an inference or output-decoding failure.
    ///
    /// The camera may still be delivering frames, but occupancy is no longer
    /// known. `unknown` is the only honest status until a later frame is
    /// analysed successfully.
    pub(crate) fn mark_inference_failure(
        &mut self,
        status: StreamStatus,
        now: Duration,
    ) -> Vec<TableEvent> {
        self.model_ready = false;
        self.mark_stream_down(status, now)
    }

    /// FFI payload for the overlay: normalised boxes with a fresh age.
    ///
    /// The age is derived here, under the same lock that owns the boxes, so a
    /// caller that reuses a cached `EngineHealth` still gets a number measured
    /// against the session clock at read time. Empty before the first
    /// successful inference and after the stream goes down.
    pub(crate) fn detection_payload(&self) -> Vec<DetectionBox> {
        let now_ms = millis(
            self.started_at
                .map_or(Duration::ZERO, |started| started.elapsed()),
        );
        self.detections
            .iter()
            .map(|detection| detection.to_payload(now_ms))
            .collect()
    }

    /// Immutable snapshots for the analysis panel, in table-id order.
    pub(crate) fn table_snapshots(&self) -> Vec<crate::types::TableSnapshot> {
        self.tables
            .iter()
            .map(|(id, table)| crate::types::TableSnapshot {
                table_id: id.clone(),
                label: table.label.clone(),
                status: table.status,
                persons_in_region: table.persons,
                confidence: table.confidence,
                since_ms: table.since_ms,
            })
            .collect()
    }

    /// Aggregate counts for the home screen cards.
    pub(crate) fn aggregate(&self) -> (u32, u32, u32) {
        let mut available = 0;
        let mut occupied = 0;
        let mut unknown = 0;
        for table in self.tables.values() {
            match table.status {
                TableStatus::Available => available += 1,
                TableStatus::Occupied => occupied += 1,
                TableStatus::Unknown => unknown += 1,
            }
        }
        (available, occupied, unknown)
    }
}

/// Lock the state, recovering from a poisoned mutex.
///
/// A panic while the lock is held is already converted to
/// [`EngineError::Internal`] at the FFI boundary; refusing all later calls
/// would turn one bug into a permanently dead engine.
pub(crate) fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Convert a pixel box to 0..=1, clamping each edge to the frame.
///
/// `frame_seq` and `captured_at` come from the frame being analysed, not from
/// the read that serves the boxes, so the overlay sees an honest age (Q-12).
fn analysed_box(
    detection: &Detection,
    frame: FrameSize,
    frame_seq: u64,
    captured_at: Duration,
) -> AnalysedBox {
    let width = f64::from(frame.width);
    let height = f64::from(frame.height);
    let left = (detection.bbox.x / width).clamp(0.0, 1.0);
    let top = (detection.bbox.y / height).clamp(0.0, 1.0);
    let right = ((detection.bbox.x + detection.bbox.width) / width).clamp(left, 1.0);
    let bottom = ((detection.bbox.y + detection.bbox.height) / height).clamp(top, 1.0);
    AnalysedBox {
        x: left as f32,
        y: top as f32,
        width: (right - left) as f32,
        height: (bottom - top) as f32,
        confidence: detection.confidence.clamp(0.0, 1.0) as f32,
        frame_seq,
        captured_at,
    }
}

pub(crate) fn millis(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

/// Shared handles owned by the FFI object.
#[derive(Debug, Clone)]
pub(crate) struct SharedState {
    pub(crate) inner: Arc<Mutex<StateInner>>,
    pub(crate) counters: Arc<Counters>,
}

impl Default for SharedState {
    fn default() -> Self {
        Self {
            inner: Arc::new(Mutex::new(StateInner::default())),
            counters: Arc::new(Counters::default()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use spatial_engine::{DebounceConfig, PixelBox};

    const NAMED_TABLES: &str = r#"{
      "version": 2,
      "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
      "tables": [
        { "id": "meja-01", "label": "Meja Dekat Pintu", "polygon": [
            { "x": 0.2, "y": 0.2 }, { "x": 0.4, "y": 0.2 },
            { "x": 0.4, "y": 0.4 }, { "x": 0.2, "y": 0.4 } ] },
        { "id": "meja-02", "label": "Meja 2", "polygon": [
            { "x": 0.6, "y": 0.2 }, { "x": 0.8, "y": 0.2 },
            { "x": 0.8, "y": 0.4 }, { "x": 0.6, "y": 0.4 } ] }
      ]
    }"#;

    const TABLES: &str = r#"{
      "version": 2,
      "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
      "tables": [
        { "id": "meja-01", "polygon": [
            { "x": 0.2, "y": 0.2 }, { "x": 0.4, "y": 0.2 },
            { "x": 0.4, "y": 0.4 }, { "x": 0.2, "y": 0.4 } ] }
      ]
    }"#;

    #[test]
    fn installing_tables_starts_every_table_unknown() {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap();
        assert_eq!(state.aggregate(), (0, 0, 1));
        assert_eq!(
            state.tables.get("meja-01").map(|table| table.status),
            Some(TableStatus::Unknown)
        );
    }

    #[test]
    fn invalid_json_is_rejected_without_touching_state() {
        let mut state = StateInner::default();
        let error = state
            .install_tables("{not json", DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap_err();
        assert!(matches!(error, EngineError::Tables { .. }));
        assert!(state.tables.is_empty());
    }

    #[test]
    fn stream_down_never_reports_available() {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap();
        let events = state.mark_stream_down(StreamStatus::Closed, Duration::from_secs(3));
        assert!(events
            .iter()
            .all(|event| event.status == TableStatus::Unknown));
        assert_eq!(state.aggregate(), (0, 0, 1));
    }

    #[test]
    fn inference_failure_marks_every_table_unknown_and_clears_model_ready() {
        let mut state = StateInner {
            model_ready: true,
            ..StateInner::default()
        };
        state
            .install_tables(TABLES, DebounceConfig::new(Duration::ZERO), Duration::ZERO)
            .unwrap();
        state.unfreeze_stream(Duration::ZERO);
        let frame = FrameSize::new(1280, 720).unwrap();
        let person = Detection::new(PixelBox::new(384.0, 216.0, 0.0, 0.0), 0.9);
        let events = state.observe_frame(&[person], frame, Duration::from_millis(10));
        state.apply_events(&events);
        assert_eq!(state.tables["meja-01"].status, TableStatus::Occupied);

        let events = state.mark_inference_failure(StreamStatus::Error, Duration::from_millis(20));
        state.apply_events(&events);

        assert!(!state.model_ready);
        assert_eq!(state.stream, StreamStatus::Error);
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].status, TableStatus::Unknown);
        assert_eq!(state.aggregate(), (0, 0, 1));
    }

    #[test]
    fn a_successful_frame_after_inference_failure_recovers_to_available() {
        let mut state = StateInner {
            model_ready: true,
            ..StateInner::default()
        };
        state
            .install_tables(TABLES, DebounceConfig::new(Duration::ZERO), Duration::ZERO)
            .unwrap();
        state.unfreeze_stream(Duration::ZERO);
        state.mark_inference_failure(StreamStatus::Error, Duration::from_millis(10));

        state.model_ready = true;
        state.stream = StreamStatus::Connected;
        state.unfreeze_stream(Duration::from_millis(20));
        let frame = FrameSize::new(1280, 720).unwrap();
        let events = state.observe_frame(&[], frame, Duration::from_millis(30));
        state.apply_events(&events);

        assert_eq!(events.len(), 1);
        assert_eq!(events[0].status, TableStatus::Available);
        assert_eq!(state.aggregate(), (1, 0, 0));
    }

    #[test]
    fn first_frame_after_install_can_flip_unknown_to_occupied() {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::new(Duration::ZERO), Duration::ZERO)
            .unwrap();

        // The frame path calls `unfreeze_stream`, which deliberately discards
        // the `available` event until a real frame is analysed.
        state.unfreeze_stream(Duration::from_millis(10));
        assert_eq!(state.tables["meja-01"].status, TableStatus::Unknown);

        // Person at the polygon centre, 1280x720.
        let person = Detection::new(PixelBox::new(384.0, 216.0, 0.0, 0.0), 0.9);
        let frame = FrameSize::new(1280, 720).unwrap();
        let events = state.observe_frame(&[person], frame, Duration::from_millis(20));
        state.apply_events(&events);

        assert_eq!(events.len(), 1);
        assert_eq!(events[0].status, TableStatus::Occupied);
        assert_eq!(events[0].persons_in_region, 1);
        assert!((events[0].confidence - 0.9).abs() < 1e-6);
        assert_eq!(state.aggregate(), (0, 1, 0));
    }

    #[test]
    fn first_empty_frame_clears_unknown_instead_of_staying_stuck() {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap();
        state.unfreeze_stream(Duration::from_millis(10));
        assert_eq!(state.tables["meja-01"].status, TableStatus::Unknown);

        let frame = FrameSize::new(1280, 720).unwrap();
        let events = state.observe_frame(&[], frame, Duration::from_millis(20));
        state.apply_events(&events);

        assert_eq!(events.len(), 1, "the UI must hear about the first frame");
        assert_eq!(events[0].status, TableStatus::Available);
        assert_eq!(events[0].persons_in_region, 0);
        assert_eq!(state.aggregate(), (1, 0, 0));
    }

    #[test]
    fn a_second_empty_frame_does_not_repeat_available() {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap();
        state.unfreeze_stream(Duration::ZERO);

        let frame = FrameSize::new(1280, 720).unwrap();
        let first = state.observe_frame(&[], frame, Duration::from_millis(20));
        state.apply_events(&first);
        let second = state.observe_frame(&[], frame, Duration::from_millis(120));

        assert_eq!(first.len(), 1);
        assert!(second.is_empty(), "steady state must not spam events");
    }

    #[test]
    fn first_occupied_frame_waits_for_debounce_instead_of_reporting_available() {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap();
        state.unfreeze_stream(Duration::from_millis(10));

        let person = Detection::new(PixelBox::new(384.0, 216.0, 0.0, 0.0), 0.9);
        let frame = FrameSize::new(1280, 720).unwrap();

        let early = state.observe_frame(&[person], frame, Duration::from_millis(20));
        state.apply_events(&early);
        assert!(
            early.is_empty(),
            "a person inside the hold window must not turn into available"
        );
        assert_eq!(state.tables["meja-01"].status, TableStatus::Unknown);

        let confirmed = state.observe_frame(&[person], frame, Duration::from_millis(2_100));
        state.apply_events(&confirmed);
        assert_eq!(confirmed.len(), 1);
        assert_eq!(confirmed[0].status, TableStatus::Occupied);
        assert_eq!(confirmed[0].persons_in_region, 1);
    }

    #[test]
    fn a_person_leaving_before_debounce_reports_available() {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap();
        state.unfreeze_stream(Duration::from_millis(10));

        let person = Detection::new(PixelBox::new(384.0, 216.0, 0.0, 0.0), 0.9);
        let frame = FrameSize::new(1280, 720).unwrap();
        let early = state.observe_frame(&[person], frame, Duration::from_millis(20));
        state.apply_events(&early);

        let empty = state.observe_frame(&[], frame, Duration::from_millis(120));
        state.apply_events(&empty);

        assert_eq!(empty.len(), 1);
        assert_eq!(empty[0].status, TableStatus::Available);
        assert_eq!(empty[0].persons_in_region, 0);
    }

    #[test]
    fn installed_tables_keep_their_labels_for_the_analysis_panel() {
        let mut state = StateInner::default();
        state
            .install_tables(NAMED_TABLES, DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap();

        let snapshots = state.table_snapshots();
        assert_eq!(snapshots.len(), 2);
        assert_eq!(snapshots[0].table_id, "meja-01");
        assert_eq!(snapshots[0].label, "Meja Dekat Pintu");
        assert_eq!(snapshots[0].status, TableStatus::Unknown);
        assert_eq!(snapshots[1].label, "Meja 2");
    }

    #[test]
    fn detection_boxes_are_normalised_and_clear_when_the_stream_drops() {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap();
        assert!(
            state.detections.is_empty(),
            "no frame has been analysed yet"
        );

        // The box straddles the right edge: it must be clamped, not negative.
        let person = Detection::new(PixelBox::new(1_200.0, 300.0, 200.0, 180.0), 0.85);
        let frame = FrameSize::new(1280, 720).unwrap();
        state.observe_frame(&[person], frame, Duration::from_millis(10));

        let boxed = &state.detections[0];
        assert!(boxed.x >= 0.0 && boxed.x <= 1.0);
        assert!(boxed.y >= 0.0 && boxed.y <= 1.0);
        assert!(boxed.width >= 0.0 && boxed.width <= 1.0);
        assert!(boxed.height >= 0.0 && boxed.height <= 1.0);
        assert!((boxed.x - 1_200.0 / 1_280.0).abs() < 1e-6);
        assert!((boxed.width - 80.0 / 1_280.0).abs() < 1e-6);

        state.mark_stream_down(StreamStatus::Closed, Duration::from_millis(20));
        assert!(
            state.detections.is_empty(),
            "a dead stream must not leave stale boxes on screen"
        );
    }

    #[test]
    fn detections_carry_the_frame_sequence_they_came_from() {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap();
        let person = Detection::new(PixelBox::new(384.0, 216.0, 0.0, 0.0), 0.9);
        let frame = FrameSize::new(1280, 720).unwrap();

        state.observe_frame(&[person], frame, Duration::from_millis(10));
        assert_eq!(state.detections[0].frame_seq, 1);
        assert_eq!(
            state.detections[0].frame_seq, state.analysis_seq,
            "a box must be stamped with the frame that produced it"
        );

        state.observe_frame(&[person], frame, Duration::from_millis(510));
        assert_eq!(
            state.detections[0].frame_seq, 2,
            "the sequence follows the analysed frame, not the reader"
        );
        // Reading the payload again must not invent a newer frame.
        assert_eq!(state.detections[0].frame_seq, 2);
    }

    #[test]
    fn box_age_is_measured_from_capture_not_from_poll() {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap();
        let person = Detection::new(PixelBox::new(384.0, 216.0, 0.0, 0.0), 0.9);
        let frame = FrameSize::new(1280, 720).unwrap();

        state.observe_frame(&[person], frame, Duration::from_millis(1_200));
        assert_eq!(
            state.detections[0].captured_at,
            Duration::from_millis(1_200),
            "the stamp is the frame's arrival time"
        );

        // Unrelated reads a moment later keep the original stamp, so the age
        // Kotlin sees keeps growing instead of being reset by the read.
        let _ = state.aggregate();
        let _ = state.table_snapshots();
        assert_eq!(
            state.detections[0].captured_at,
            Duration::from_millis(1_200)
        );
        assert_eq!(state.detections[0].frame_seq, 1);
    }

    #[test]
    fn payload_age_is_derived_from_the_capture_stamp_at_read_time() {
        let boxed = AnalysedBox {
            x: 0.1,
            y: 0.2,
            width: 0.05,
            height: 0.3,
            confidence: 0.8,
            frame_seq: 7,
            captured_at: Duration::from_millis(1_000),
        };

        let fresh = boxed.to_payload(1_050);
        assert_eq!(fresh.frame_seq, 7);
        assert_eq!(fresh.captured_at_ms, 1_000);
        assert_eq!(fresh.age_ms, 50);

        // The same analysis served 1,4 s later must look old, not new.
        let stale = boxed.to_payload(2_400);
        assert_eq!(stale.captured_at_ms, 1_000);
        assert_eq!(
            stale.age_ms, 1_400,
            "a read must report the box's real age, not zero"
        );

        // A session clock that lags must clamp to zero, never wrap to ~2^64.
        assert_eq!(boxed.to_payload(900).age_ms, 0);
    }

    #[test]
    fn an_empty_frame_advances_the_sequence_without_leaving_boxes() {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::default_hold(), Duration::ZERO)
            .unwrap();
        let person = Detection::new(PixelBox::new(384.0, 216.0, 0.0, 0.0), 0.9);
        let frame = FrameSize::new(1280, 720).unwrap();

        state.observe_frame(&[person], frame, Duration::from_millis(10));
        state.observe_frame(&[], frame, Duration::from_millis(510));
        assert!(
            state.detections.is_empty(),
            "an analysed frame with nobody clears the previous boxes"
        );
        assert_eq!(
            state.analysis_seq, 2,
            "the sequence still counts the empty analysis"
        );

        state.observe_frame(&[person], frame, Duration::from_millis(1_010));
        assert_eq!(
            state.detections[0].frame_seq, 3,
            "the next box must not reuse the sequence of an older frame"
        );
    }

    #[test]
    fn an_empty_inference_window_reports_zero_instead_of_a_fake_latency() {
        let samples = VecDeque::new();
        assert_eq!(percentile_ms(&samples, 50.0), 0);
        assert_eq!(percentile_ms(&samples, 95.0), 0);
    }

    #[test]
    fn p50_and_p95_use_nearest_rank_on_the_recorded_window() {
        let mut samples = VecDeque::new();
        for elapsed in 10..=100 {
            record_inference_sample(&mut samples, Duration::from_millis(elapsed));
        }
        assert_eq!(samples.len(), 91);
        assert_eq!(percentile_ms(&samples, 50.0), 55);
        // Nearest rank: ceil(0.95 * 91) = 87 -> the 87th smallest sample, 96 ms.
        assert_eq!(percentile_ms(&samples, 95.0), 96);
    }

    #[test]
    fn the_inference_window_never_grows_past_its_cap() {
        let mut samples = VecDeque::new();
        for elapsed in 1..=500 {
            record_inference_sample(&mut samples, Duration::from_millis(elapsed));
        }
        assert_eq!(samples.len(), MAX_INFERENCE_SAMPLES);
        assert_eq!(samples.front().copied(), Some(Duration::from_millis(381)));
        assert_eq!(samples.back().copied(), Some(Duration::from_millis(500)));
    }

    #[test]
    fn a_new_session_starts_with_an_empty_latency_window() {
        let mut state = StateInner::default();
        record_inference_sample(&mut state.inference_samples, Duration::from_millis(42));
        state.begin_session(Instant::now());
        assert!(state.inference_samples.is_empty());
    }

    /// Synthetic RGB888 signature: uniform grey with blocks painted in
    /// normalised coordinates, the same space a table map uses.
    fn signature_with_blocks(blocks: &[(f64, f64, f64, f64, u8)]) -> ViewSignature {
        const WIDTH: u32 = 320;
        const HEIGHT: u32 = 180;
        let mut rgb = vec![60u8; (WIDTH * HEIGHT * 3) as usize];
        for &(x0, y0, x1, y1, value) in blocks {
            let xs = (x0 * f64::from(WIDTH)) as usize;
            let xe = (x1 * f64::from(WIDTH)) as usize;
            let ys = (y0 * f64::from(HEIGHT)) as usize;
            let ye = (y1 * f64::from(HEIGHT)) as usize;
            for y in ys..ye {
                for x in xs..xe {
                    let offset = (y * WIDTH as usize + x) * 3;
                    rgb[offset..offset + 3].copy_from_slice(&[value, value, value]);
                }
            }
        }
        ViewSignature::from_rgb888(&rgb, WIDTH, HEIGHT).expect("synthetic frame is 320x180")
    }

    fn view_with_wall_on_the_left() -> ViewSignature {
        signature_with_blocks(&[(0.0, 0.0, 0.3, 1.0, 200)])
    }

    fn view_with_wall_on_the_right() -> ViewSignature {
        signature_with_blocks(&[(0.7, 0.0, 1.0, 1.0, 200)])
    }

    fn frame_16_9() -> FrameSize {
        FrameSize::new(320, 180).expect("320x180 is a valid frame")
    }

    /// Feed one view until the guard flips, mirroring Q-09's streak requirement.
    ///
    /// The guard needs `enter_frames` consecutive changed frames before it
    /// reports a pan, so a test that wants "the camera moved" must observe the
    /// new view more than once.
    fn observe_view_until_moved(state: &mut StateInner, signature: &ViewSignature, from_ms: u64) {
        for step in 0..4u64 {
            let verdict = state.observe_view(signature, Duration::from_millis(from_ms + step));
            if verdict.moved {
                return;
            }
        }
        panic!("view was never flagged moved");
    }

    /// Feed one view until the guard releases, mirroring Q-09.
    fn observe_view_until_recovered(
        state: &mut StateInner,
        signature: &ViewSignature,
        from_ms: u64,
    ) {
        for step in 0..4u64 {
            let verdict = state.observe_view(signature, Duration::from_millis(from_ms + step));
            if !verdict.moved {
                return;
            }
        }
        panic!("view never recovered");
    }

    /// Person at the centre of the `meja-01` polygon (0.2..0.4 on both axes).
    fn person_at_meja_01() -> Detection {
        Detection::new(PixelBox::new(96.0, 54.0, 0.0, 0.0), 0.9)
    }

    fn occupied_state() -> StateInner {
        let mut state = StateInner::default();
        state
            .install_tables(TABLES, DebounceConfig::new(Duration::ZERO), Duration::ZERO)
            .unwrap();
        state.unfreeze_stream(Duration::ZERO);
        state.observe_view(&view_with_wall_on_the_left(), Duration::from_millis(10));
        let events = state.observe_frame(&[person_at_meja_01()], frame_16_9(), Duration::ZERO);
        state.apply_events(&events);
        assert_eq!(state.tables["meja-01"].status, TableStatus::Occupied);
        state
    }

    #[test]
    fn a_panned_camera_never_reports_a_confident_table_status() {
        let mut state = occupied_state();

        observe_view_until_moved(&mut state, &view_with_wall_on_the_right(), 20);
        assert!(state.view.moved);

        let events = state.observe_frame(
            &[person_at_meja_01()],
            frame_16_9(),
            Duration::from_millis(20),
        );
        state.apply_events(&events);

        assert_eq!(events.len(), 1);
        assert_eq!(events[0].status, TableStatus::Unknown);
        assert_eq!(state.aggregate(), (0, 0, 1));
        assert!(state.needs_recalibration, "the UI must ask for a new map");
    }

    #[test]
    fn a_person_walking_through_the_room_keeps_the_reading() {
        let mut state = occupied_state();

        // Someone crossing the frame is not a camera that moved.
        let verdict = state.observe_view(
            &signature_with_blocks(&[(0.0, 0.0, 0.3, 1.0, 200), (0.45, 0.2, 0.55, 0.8, 200)]),
            Duration::from_millis(20),
        );
        assert!(!verdict.moved);

        let events = state.observe_frame(
            &[person_at_meja_01()],
            frame_16_9(),
            Duration::from_millis(20),
        );
        assert!(
            events.is_empty(),
            "an unchanged reading must not spam events"
        );
        assert_eq!(state.tables["meja-01"].status, TableStatus::Occupied);
    }

    #[test]
    fn the_map_works_again_once_the_camera_returns() {
        let mut state = occupied_state();
        observe_view_until_moved(&mut state, &view_with_wall_on_the_right(), 20);
        let events = state.observe_frame(
            &[person_at_meja_01()],
            frame_16_9(),
            Duration::from_millis(20),
        );
        state.apply_events(&events);

        observe_view_until_recovered(&mut state, &view_with_wall_on_the_left(), 40);
        assert!(!state.view.moved);

        let events = state.observe_frame(&[], frame_16_9(), Duration::from_millis(40));
        state.apply_events(&events);

        assert_eq!(events.len(), 1);
        assert_eq!(events[0].status, TableStatus::Available);
        assert_eq!(state.aggregate(), (1, 0, 0));
    }

    #[test]
    fn installing_a_new_map_accepts_whatever_the_camera_shows_now() {
        let mut state = occupied_state();
        observe_view_until_moved(&mut state, &view_with_wall_on_the_right(), 20);
        assert!(state.view.moved);

        state
            .install_tables(
                TABLES,
                DebounceConfig::new(Duration::ZERO),
                Duration::from_millis(30),
            )
            .unwrap();
        state.unfreeze_stream(Duration::from_millis(30));

        let verdict = state.observe_view(&view_with_wall_on_the_right(), Duration::from_millis(40));
        assert!(
            !verdict.moved,
            "the new baseline is captured on the new view"
        );

        let events = state.observe_frame(&[], frame_16_9(), Duration::from_millis(40));
        state.apply_events(&events);
        assert_eq!(state.aggregate(), (1, 0, 0));
    }
}
