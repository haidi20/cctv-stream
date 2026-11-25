//! Assignment rules for adjacent tables.

use std::time::Duration;

use spatial_engine::{
    assign, DebounceConfig, NormalizedDetection, Point, Polygon, TableStateMachine,
    DEFAULT_MIN_CONFIDENCE,
};

fn square(x0: f64, y0: f64, x1: f64, y1: f64) -> Polygon {
    Polygon::new(vec![
        Point::new(x0, y0),
        Point::new(x1, y0),
        Point::new(x1, y1),
        Point::new(x0, y1),
    ])
    .unwrap()
}

fn machines(count: usize) -> Vec<TableStateMachine> {
    (0..count)
        .map(|i| {
            TableStateMachine::new(
                format!("meja-{i}"),
                DebounceConfig::new(Duration::from_secs(1)),
                Duration::ZERO,
            )
        })
        .collect()
}

#[test]
fn two_people_at_two_tables_are_counted_separately() {
    let tables = machines(2);
    let footprints = vec![
        ("a", square(0.1, 0.1, 0.3, 0.3)),
        ("b", square(0.6, 0.6, 0.8, 0.8)),
    ];

    let assignment = assign(
        &[
            NormalizedDetection {
                centroid: Point::new(0.2, 0.2),
                confidence: 0.9,
            },
            NormalizedDetection {
                centroid: Point::new(0.7, 0.7),
                confidence: 0.9,
            },
        ],
        &tables,
        &footprints,
        DEFAULT_MIN_CONFIDENCE,
    );

    assert_eq!(assignment.per_table, vec![1, 1]);
}

#[test]
fn overlap_assigns_to_the_nearest_table_only() {
    let tables = machines(2);
    let footprints = vec![
        ("a", square(0.10, 0.1, 0.30, 0.3)),
        ("b", square(0.25, 0.1, 0.45, 0.3)),
    ];

    let assignment = assign(
        &[NormalizedDetection {
            centroid: Point::new(0.29, 0.2),
            confidence: 0.9,
        }],
        &tables,
        &footprints,
        DEFAULT_MIN_CONFIDENCE,
    );

    assert_eq!(
        assignment.per_table.iter().sum::<usize>(),
        1,
        "one person must not occupy two tables"
    );
}

#[test]
fn confidence_threshold_matches_the_contract() {
    assert_eq!(DEFAULT_MIN_CONFIDENCE, 0.35);
}

#[test]
fn passer_by_outside_the_footprint_is_unassigned() {
    let tables = machines(1);
    let footprints = vec![("a", square(0.1, 0.1, 0.3, 0.3))];

    let assignment = assign(
        &[NormalizedDetection {
            centroid: Point::new(0.9, 0.9),
            confidence: 0.99,
        }],
        &tables,
        &footprints,
        DEFAULT_MIN_CONFIDENCE,
    );

    assert_eq!(assignment.per_table, vec![0]);
    assert_eq!(assignment.unassigned, 1);
}
