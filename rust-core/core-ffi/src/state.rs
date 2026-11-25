//! Engine state shared between FFI calls and the watchdog.

use std::collections::{BTreeMap, BTreeSet};
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use spatial_engine::{
    DebounceConfig, Detection, FrameSize, NormalizedDetection, SpatialEngine, TableConfigSet,
    DEFAULT_MIN_CONFIDENCE,
};

use crate::error::EngineError;
use crate::types::{StreamStatus, TableEvent, TableStatus};

/// Snapshot of table plus per-table bookkeeping.
#[derive(Debug, Clone)]
pub(crate) struct TableSnapshot {
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
    pub(crate) connects: AtomicU32,
    pub(crate) reconnect_failures: AtomicU32,
}

impl Counters {
    pub(crate) fn reset(&self) {
        self.frames_in.store(0, Ordering::Relaxed);
        self.frames_sampled.store(0, Ordering::Relaxed);
        self.connects.store(0, Ordering::Relaxed);
        self.reconnect_failures.store(0, Ordering::Relaxed);
    }
}

/// Mutable state guarded by one lock.
#[derive(Debug)]
pub(crate) struct StateInner {
    pub(crate) stream: StreamStatus,
    pub(crate) needs_recalibration: bool,
    pub(crate) model_ready: bool,
    pub(crate) config: Option<TableConfigSet>,
    pub(crate) spatial: Option<SpatialEngine>,
    pub(crate) tables: BTreeMap<String, TableSnapshot>,
    pub(crate) last_frame_at: Option<Instant>,
    pub(crate) started_at: Option<Instant>,
    pub(crate) last_inference_at: Option<Instant>,
    pub(crate) inference_interval: Duration,
}

impl Default for StateInner {
    fn default() -> Self {
        Self {
            stream: StreamStatus::Idle,
            needs_recalibration: false,
            model_ready: false,
            config: None,
            spatial: None,
            tables: BTreeMap::new(),
            last_frame_at: None,
            started_at: None,
            last_inference_at: None,
            inference_interval: Duration::from_secs_f64(1.0 / 5.0),
        }
    }
}

impl StateInner {
    /// Reset everything for a new session, keeping only the table JSON.
    pub(crate) fn begin_session(&mut self, now: Instant) {
        self.stream = StreamStatus::Connecting;
        self.needs_recalibration = false;
        self.last_frame_at = None;
        self.last_inference_at = None;
        self.started_at = Some(now);
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
        for id in spatial.table_ids() {
            tables.insert(
                id.to_string(),
                TableSnapshot {
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
        self.stream = StreamStatus::Connecting;
        self.needs_recalibration = false;
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

        let normalized: Vec<NormalizedDetection> = detections
            .iter()
            .map(|detection| NormalizedDetection {
                centroid: detection.centroid(frame),
                confidence: detection.confidence,
            })
            .collect();

        // `SpatialEngine::update_normalized` assumes confidence 1.0 in this
        // path; assignment below is the single source of per-table counts.
        let raw = self
            .spatial
            .as_mut()
            .map(|spatial| spatial.update_normalized(&normalized, now))
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
            let (persons, confidence) =
                table_totals(&normalized, self.config.as_ref(), event.table_index);
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
            let (persons, confidence) = table_totals(&normalized, self.config.as_ref(), index);
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

/// Count and confidence for one table from normalised detections.
///
/// Mirrors [`spatial_engine::assign`] so the event that the state machine
/// produced carries the same numbers the debounce used.
fn table_totals(
    detections: &[NormalizedDetection],
    config: Option<&TableConfigSet>,
    table_index: usize,
) -> (usize, f32) {
    let Some(config) = config else {
        return (0, 0.0);
    };
    if config.tables.get(table_index).is_none() {
        return (0, 0.0);
    }
    let mut persons = 0_usize;
    let mut best = 0.0_f64;
    for detection in detections {
        if detection.confidence < DEFAULT_MIN_CONFIDENCE {
            continue;
        }

        // Mirror `spatial_engine::assign`: a person inside several polygons is
        // assigned to the table whose centre is nearest, never to both.
        let mut winner = None;
        let mut winner_distance = f64::INFINITY;
        for (index, candidate) in config.tables.iter().enumerate() {
            if !candidate.polygon.contains(detection.centroid) {
                continue;
            }
            let candidate_centre = candidate.polygon.centroid();
            let dx = detection.centroid.x - candidate_centre.x;
            let dy = detection.centroid.y - candidate_centre.y;
            let distance = dx * dx + dy * dy;
            if winner.is_none() || distance < winner_distance {
                winner = Some(index);
                winner_distance = distance;
            }
        }

        if winner != Some(table_index) {
            continue;
        }

        persons += 1;
        best = best.max(detection.confidence);
    }

    let confidence = if persons == 0 {
        0.0
    } else {
        // Detector confidences are 0..=1; clamp before narrowing to f32.
        best.clamp(0.0, 1.0) as f32
    };
    (persons, confidence)
}

/// Lock the state, recovering from a poisoned mutex.
///
/// A panic while the lock is held is already converted to
/// [`EngineError::Internal`] at the FFI boundary; refusing all later calls
/// would turn one bug into a permanently dead engine.
pub(crate) fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
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
}
