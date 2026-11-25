//! Assignment of detections to table footprints.

use crate::geometry::FrameSize;
use crate::polygon::Point;
use crate::state_machine::TableStateMachine;

/// A person detection already converted to normalised coordinates.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NormalizedDetection {
    /// Centroid in `0..=1`.
    pub centroid: Point,
    /// Model confidence.
    pub confidence: f64,
}

/// Minimum confidence treated as a person.
pub const DEFAULT_MIN_CONFIDENCE: f64 = 0.35;

/// Counts per table after assignment.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Assignment {
    /// Counts aligned with the table order used during assignment.
    pub per_table: Vec<usize>,
    /// Detections that fell inside no footprint or lost a tie.
    pub unassigned: usize,
}

/// Assign each detection to at most one table.
///
/// A detection inside several footprints goes to the table whose centre is
/// closest, which prevents one person from occupying two adjacent tables.
pub fn assign(
    detections: &[NormalizedDetection],
    tables: &[TableStateMachine],
    footprints: &[(&str, crate::polygon::Polygon)],
    min_confidence: f64,
) -> Assignment {
    let mut per_table = vec![0usize; tables.len()];
    let mut unassigned = 0;

    for detection in detections {
        if detection.confidence < min_confidence {
            continue;
        }

        let mut best: Option<(usize, f64)> = None;
        for (index, (_, polygon)) in footprints.iter().enumerate() {
            if index >= tables.len() || !polygon.contains(detection.centroid) {
                continue;
            }

            let centre = polygon.centroid();
            let dx = detection.centroid.x - centre.x;
            let dy = detection.centroid.y - centre.y;
            let distance = dx * dx + dy * dy;

            match best {
                Some((_, current)) if current <= distance => {}
                _ => best = Some((index, distance)),
            }
        }

        match best {
            Some((index, _)) => per_table[index] += 1,
            None => unassigned += 1,
        }
    }

    Assignment {
        per_table,
        unassigned,
    }
}

/// Count detections assigned to one table.
pub fn count_for(
    detections: &[NormalizedDetection],
    footprints: &[crate::polygon::Polygon],
    table_index: usize,
    min_confidence: f64,
) -> usize {
    if table_index >= footprints.len() {
        return 0;
    }

    detections
        .iter()
        .filter(|detection| detection.confidence >= min_confidence)
        .filter(|detection| footprints[table_index].contains(detection.centroid))
        .count()
}

/// Convert the frame-size guard into a calibration verdict.
pub fn calibration_needed(configured: crate::geometry::AspectRatio, frame: FrameSize) -> bool {
    crate::geometry::needs_recalibration(configured, frame)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::debounce::DebounceConfig;
    use crate::polygon::Polygon;
    use std::time::Duration;

    fn square(x0: f64, y0: f64, x1: f64, y1: f64) -> Polygon {
        Polygon::new(vec![
            Point::new(x0, y0),
            Point::new(x1, y0),
            Point::new(x1, y1),
            Point::new(x0, y1),
        ])
        .unwrap()
    }

    fn machine(id: &str) -> TableStateMachine {
        TableStateMachine::new(
            id,
            DebounceConfig::new(Duration::from_secs(1)),
            Duration::ZERO,
        )
    }

    #[test]
    fn assigns_one_person_to_one_table() {
        let tables = vec![machine("a")];
        let footprint = square(0.1, 0.1, 0.4, 0.4);
        let footprints: Vec<(&str, Polygon)> = vec![("a", footprint)];

        let assignment = assign(
            &[NormalizedDetection {
                centroid: Point::new(0.2, 0.2),
                confidence: 0.9,
            }],
            &tables,
            &footprints,
            DEFAULT_MIN_CONFIDENCE,
        );

        assert_eq!(assignment.per_table, vec![1]);
        assert_eq!(assignment.unassigned, 0);
    }

    #[test]
    fn overlapping_tables_use_the_nearest_centroid() {
        let tables = vec![machine("a"), machine("b")];
        let footprints: Vec<(&str, Polygon)> = vec![
            ("a", square(0.10, 0.1, 0.30, 0.3)),
            ("b", square(0.25, 0.1, 0.45, 0.3)),
        ];

        let assignment = assign(
            &[NormalizedDetection {
                centroid: Point::new(0.28, 0.2),
                confidence: 0.9,
            }],
            &tables,
            &footprints,
            DEFAULT_MIN_CONFIDENCE,
        );

        assert_eq!(assignment.per_table, vec![0, 1]);
    }

    #[test]
    fn low_confidence_detections_are_ignored() {
        let tables = vec![machine("a")];
        let footprints: Vec<(&str, Polygon)> = vec![("a", square(0.1, 0.1, 0.4, 0.4))];

        let assignment = assign(
            &[NormalizedDetection {
                centroid: Point::new(0.2, 0.2),
                confidence: 0.1,
            }],
            &tables,
            &footprints,
            DEFAULT_MIN_CONFIDENCE,
        );

        assert_eq!(assignment.per_table, vec![0]);
    }

    #[test]
    fn detections_outside_every_table_are_unassigned() {
        let tables = vec![machine("a")];
        let footprints: Vec<(&str, Polygon)> = vec![("a", square(0.1, 0.1, 0.4, 0.4))];

        let assignment = assign(
            &[NormalizedDetection {
                centroid: Point::new(0.8, 0.8),
                confidence: 0.9,
            }],
            &tables,
            &footprints,
            DEFAULT_MIN_CONFIDENCE,
        );

        assert_eq!(assignment.per_table, vec![0]);
        assert_eq!(assignment.unassigned, 1);
    }

    #[test]
    fn person_moving_between_tables_switches_assignment() {
        let tables = vec![machine("a"), machine("b")];
        let footprints: Vec<(&str, Polygon)> = vec![
            ("a", square(0.1, 0.1, 0.3, 0.3)),
            ("b", square(0.6, 0.6, 0.8, 0.8)),
        ];

        let first = assign(
            &[NormalizedDetection {
                centroid: Point::new(0.2, 0.2),
                confidence: 0.9,
            }],
            &tables,
            &footprints,
            DEFAULT_MIN_CONFIDENCE,
        );
        assert_eq!(first.per_table, vec![1, 0]);

        let second = assign(
            &[NormalizedDetection {
                centroid: Point::new(0.7, 0.7),
                confidence: 0.9,
            }],
            &tables,
            &footprints,
            DEFAULT_MIN_CONFIDENCE,
        );
        assert_eq!(second.per_table, vec![0, 1]);
    }
}
