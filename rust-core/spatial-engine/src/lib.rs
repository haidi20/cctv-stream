//! Table occupancy geometry and status state machine.
//!
//! Everything here runs in normalised `0..=1` frame space, so the same
//! configuration survives resolution changes as long as the aspect ratio is
//! preserved (ADR 0008).

pub mod centroid;
pub mod debounce;
pub mod error;
pub mod geometry;
pub mod loader;
pub mod multi_table;
pub mod polygon;
pub mod state_machine;

pub use centroid::Detection;
pub use debounce::{DebounceConfig, Debounced};
pub use error::ConfigError;
pub use geometry::{
    centroid_normalized, needs_recalibration, to_normalized, AspectRatio, FrameSize, PixelBox,
};
pub use loader::{TableConfig, TableConfigSet, CURRENT_VERSION};
pub use multi_table::{assign, Assignment, NormalizedDetection, DEFAULT_MIN_CONFIDENCE};
pub use polygon::{Point, Polygon, EDGE_TOLERANCE, MIN_POLYGON_AREA};
pub use state_machine::{TableState, TableStateMachine, TableStatus};

use std::time::Duration;

/// Engine that owns every table state machine.
#[derive(Debug, Clone)]
pub struct SpatialEngine {
    config: TableConfigSet,
    debounce: DebounceConfig,
    tables: Vec<TableStateMachine>,
}

/// One status change produced by the engine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EngineEvent {
    /// Index into [`SpatialEngine::table_ids`].
    pub table_index: usize,
    /// New state.
    pub state: TableState,
}

impl SpatialEngine {
    /// Build an engine from a validated configuration.
    pub fn new(config: TableConfigSet, debounce: DebounceConfig, now: Duration) -> Self {
        let tables = config
            .tables
            .iter()
            .map(|table| TableStateMachine::new(table.id.clone(), debounce, now))
            .collect();

        Self {
            config,
            debounce,
            tables,
        }
    }

    /// Configuration in use.
    pub fn config(&self) -> &TableConfigSet {
        &self.config
    }

    /// Debounce configuration.
    pub fn debounce(&self) -> DebounceConfig {
        self.debounce
    }

    /// Table identifiers in engine order.
    pub fn table_ids(&self) -> Vec<&str> {
        self.tables.iter().map(TableStateMachine::id).collect()
    }

    /// Current state for one table.
    pub fn state(&self, table_id: &str) -> Option<TableState> {
        self.tables
            .iter()
            .find(|table| table.id() == table_id)
            .map(TableStateMachine::state)
    }

    /// Feed one frame worth of detections.
    pub fn update(
        &mut self,
        detections: &[PixelBox],
        frame: FrameSize,
        now: Duration,
    ) -> Vec<EngineEvent> {
        let normalized: Vec<NormalizedDetection> = detections
            .iter()
            .map(|bbox| NormalizedDetection {
                centroid: centroid_normalized(*bbox, frame),
                confidence: 1.0,
            })
            .collect();

        self.update_normalized(&normalized, now)
    }

    /// Feed detections that already carry confidence and normalised centres.
    pub fn update_normalized(
        &mut self,
        detections: &[NormalizedDetection],
        now: Duration,
    ) -> Vec<EngineEvent> {
        let footprints: Vec<(&str, Polygon)> = self
            .config
            .tables
            .iter()
            .map(|table| (table.id.as_str(), table.polygon.clone()))
            .collect();

        let assignment = assign(
            detections,
            &self.tables,
            &footprints,
            DEFAULT_MIN_CONFIDENCE,
        );

        let mut events = Vec::new();
        for (index, table) in self.tables.iter_mut().enumerate() {
            let count = assignment.per_table.get(index).copied().unwrap_or(0);
            if let Some(state) = table.observe(count, now) {
                events.push(EngineEvent {
                    table_index: index,
                    state,
                });
            }
        }
        events
    }

    /// Mark every table `unknown` when the source drops.
    pub fn stream_down(&mut self, now: Duration) -> Vec<EngineEvent> {
        self.tables
            .iter_mut()
            .enumerate()
            .filter_map(|(index, table)| {
                table.stream_down(now).map(|state| EngineEvent {
                    table_index: index,
                    state,
                })
            })
            .collect()
    }

    /// Mark every table `available` after the source recovers.
    pub fn stream_up(&mut self, now: Duration) -> Vec<EngineEvent> {
        self.tables
            .iter_mut()
            .enumerate()
            .filter_map(|(index, table)| {
                table.stream_up(now).map(|state| EngineEvent {
                    table_index: index,
                    state,
                })
            })
            .collect()
    }

    /// True when the configured aspect ratio no longer matches the stream.
    pub fn needs_recalibration(&self, frame: FrameSize) -> bool {
        needs_recalibration(self.config.aspect_ratio, frame)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CONFIG: &str = include_str!("../../../contracts/fixtures/valid/meja-mock.json");

    fn engine() -> SpatialEngine {
        SpatialEngine::new(
            TableConfigSet::from_json(CONFIG).unwrap(),
            DebounceConfig::new(Duration::from_secs(2)),
            Duration::ZERO,
        )
    }

    #[test]
    fn polygon_survives_resolution_change_when_aspect_matches() {
        let engine = engine();
        assert!(!engine.needs_recalibration(FrameSize::new(960, 540).unwrap()));
        assert!(!engine.needs_recalibration(FrameSize::new(1280, 720).unwrap()));
        assert!(!engine.needs_recalibration(FrameSize::new(768, 432).unwrap()));
    }

    #[test]
    fn aspect_change_requires_recalibration() {
        let engine = engine();
        assert!(engine.needs_recalibration(FrameSize::new(1024, 768).unwrap()));
        assert_eq!(engine.table_ids(), vec!["meja-mock"]);
    }

    #[test]
    fn person_inside_polygon_occupies_after_debounce() {
        let mut engine = engine();
        let frame = FrameSize::new(1280, 720).unwrap();
        let person = PixelBox::new(384.0, 216.0, 0.0, 0.0);

        assert!(engine.update(&[person], frame, Duration::ZERO).is_empty());
        let events = engine.update(&[person], frame, Duration::from_secs(2));
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].state.status, TableStatus::Occupied);
    }

    #[test]
    fn person_outside_polygon_keeps_table_available() {
        let mut engine = engine();
        let frame = FrameSize::new(1280, 720).unwrap();
        let person = PixelBox::new(1000.0, 500.0, 0.0, 0.0);

        assert!(engine.update(&[person], frame, Duration::ZERO).is_empty());
        assert!(engine
            .update(&[person], frame, Duration::from_secs(2))
            .is_empty());
        assert_eq!(
            engine.state("meja-mock").unwrap().status,
            TableStatus::Available
        );
    }

    #[test]
    fn unreachable_camera_marks_tables_unknown() {
        let mut engine = engine();
        let frame = FrameSize::new(1280, 720).unwrap();
        let person = PixelBox::new(384.0, 216.0, 0.0, 0.0);
        engine.update(&[person], frame, Duration::ZERO);
        engine.update(&[person], frame, Duration::from_secs(2));

        let events = engine.stream_down(Duration::from_millis(2_500));
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].state.status, TableStatus::Unknown);
        assert_eq!(
            engine.stream_down(Duration::from_secs(30)).len(),
            0,
            "unknown must be reported once, not every tick"
        );
    }

    #[test]
    fn empty_frames_flip_back_after_debounce() {
        let mut engine = engine();
        let frame = FrameSize::new(1280, 720).unwrap();
        let person = PixelBox::new(384.0, 216.0, 0.0, 0.0);
        engine.update(&[person], frame, Duration::ZERO);
        engine.update(&[person], frame, Duration::from_secs(2));

        assert!(engine.update(&[], frame, Duration::from_secs(3)).is_empty());
        let events = engine.update(&[], frame, Duration::from_secs(5));
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].state.status, TableStatus::Available);
    }
}
