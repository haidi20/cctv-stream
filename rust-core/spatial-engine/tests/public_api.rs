//! The surface Kotlin reaches through FFI.

use std::time::Duration;

use spatial_engine::{
    DebounceConfig, FrameSize, PixelBox, SpatialEngine, TableConfigSet, TableStatus,
    CURRENT_VERSION,
};

const CONFIG: &str = include_str!("../../../contracts/fixtures/valid/meja-mock.json");

fn engine() -> SpatialEngine {
    SpatialEngine::new(
        TableConfigSet::from_json(CONFIG).unwrap(),
        DebounceConfig::new(Duration::from_secs(2)),
        Duration::ZERO,
    )
}

#[test]
fn current_version_is_two() {
    assert_eq!(CURRENT_VERSION, 2);
}

#[test]
fn update_returns_events_only_on_transition() {
    let mut engine = engine();
    let frame = FrameSize::new(1280, 720).unwrap();
    let person = PixelBox::new(384.0, 216.0, 0.0, 0.0);

    assert!(engine.update(&[person], frame, Duration::ZERO).is_empty());
    assert_eq!(
        engine
            .update(&[person], frame, Duration::from_secs(2))
            .len(),
        1
    );
    assert!(engine
        .update(&[person], frame, Duration::from_secs(10))
        .is_empty());
}

#[test]
fn stream_failure_yields_unknown_once() {
    let mut engine = engine();
    let frame = FrameSize::new(1280, 720).unwrap();
    engine.update(
        &[PixelBox::new(384.0, 216.0, 0.0, 0.0)],
        frame,
        Duration::ZERO,
    );
    engine.update(
        &[PixelBox::new(384.0, 216.0, 0.0, 0.0)],
        frame,
        Duration::from_secs(2),
    );

    let events = engine.stream_down(Duration::from_secs(3));
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].state.status, TableStatus::Unknown);
    assert!(engine.stream_down(Duration::from_secs(4)).is_empty());
}

#[test]
fn engine_exposes_ids_not_pointers() {
    let engine = engine();
    assert_eq!(engine.table_ids(), vec!["meja-mock"]);
    assert!(engine.state("meja-mock").is_some());
    assert!(engine.state("does-not-exist").is_none());
}

#[test]
fn recalibration_guard_uses_aspect_not_pixels() {
    let engine = engine();
    assert!(!engine.needs_recalibration(FrameSize::new(768, 432).unwrap()));
    assert!(engine.needs_recalibration(FrameSize::new(860, 480).unwrap()));
}
