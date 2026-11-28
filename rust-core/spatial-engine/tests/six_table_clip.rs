//! Six-table validation fixture for the dev clip.
//!
//! The clip is 768x432 (16:9) and has six occupied tables. The centroids below
//! are scenario points inside the six configured footprints, not claims from a
//! real detector: no model weights ship with the repo. They exercise the Rust
//! geometry path end-to-end (assignment, debounce, and status) so a broken
//! footprint or a table mapped twice fails on every host, without a device.

use std::time::Duration;

use spatial_engine::{
    needs_recalibration, DebounceConfig, FrameSize, NormalizedDetection, Point, SpatialEngine,
    TableConfigSet, TableStatus,
};

const SIX_TABLES: &str = include_str!("../../../contracts/fixtures/valid/meja-enam-orang.json");

fn people() -> Vec<NormalizedDetection> {
    [
        (0.16, 0.64),
        (0.42, 0.41),
        (0.70, 0.62),
        (0.48, 0.80),
        (0.16, 0.20),
        (0.83, 0.18),
    ]
    .iter()
    .map(|&(x, y)| NormalizedDetection {
        centroid: Point::new(x, y),
        confidence: 0.9,
    })
    .collect()
}

fn engine() -> SpatialEngine {
    SpatialEngine::new(
        TableConfigSet::from_json(SIX_TABLES).unwrap(),
        DebounceConfig::new(Duration::from_secs(1)),
        Duration::ZERO,
    )
}

#[test]
fn all_six_occupied_tables_are_counted_from_the_clip_ground_truth() {
    let config = TableConfigSet::from_json(SIX_TABLES).unwrap();
    assert_eq!(config.len(), 6);
    assert!(!needs_recalibration(
        config.aspect_ratio,
        FrameSize::new(768, 432).unwrap()
    ));

    let mut engine = engine();
    let (_, totals) = engine.update_normalized_with_totals(&people(), Duration::ZERO);

    assert_eq!(
        totals.iter().map(|total| total.persons).collect::<Vec<_>>(),
        vec![1, 1, 1, 1, 1, 1],
        "each of the six tables must see exactly one person"
    );
    assert_eq!(
        engine.table_ids(),
        vec!["meja-01", "meja-02", "meja-03", "meja-04", "meja-05", "meja-06"]
    );
}

#[test]
fn a_box_from_the_wrong_table_must_not_silently_count_as_the_right_one() {
    let mut engine = engine();
    let mut detections = people();
    detections[0].centroid = Point::new(0.95, 0.95);

    let (_, totals) = engine.update_normalized_with_totals(&detections, Duration::ZERO);

    assert_eq!(
        totals[0].persons, 0,
        "an out-of-footprint box is unassigned"
    );
    assert_eq!(totals.iter().map(|total| total.persons).sum::<usize>(), 5);
}

#[test]
fn six_tables_occupy_all_six_states_after_debounce() {
    let mut engine = engine();
    let detections = people();

    let first = engine
        .update_normalized_with_totals(&detections, Duration::ZERO)
        .0;
    assert!(
        first.is_empty(),
        "one frame must not flip a debounced state"
    );
    let second = engine
        .update_normalized_with_totals(&detections, Duration::from_secs(1))
        .0;

    assert_eq!(second.len(), 6);
    assert!(second
        .iter()
        .all(|event| event.state.status == TableStatus::Occupied));
}
