//! Region of interest: the part of the frame the user mapped for analysis.
//!
//! The app draws the mapped scope as one blue outline and every detected person
//! inside it as a green box (Q-13 in `docs/detection-quality-todo.md`). That
//! scope must not be guessed in Kotlin: it is exactly the area covered by the
//! table polygons the user drew by hand, so Rust derives it from the same
//! configuration that decides table status and publishes it to the overlay.
//!
//! The region is the axis-aligned rectangle around every mapped polygon rather
//! than the union of the polygons. A single rectangle is what the operator can
//! actually read on screen (a union of disjoint footprints has holes and lobes
//! that look like a second, different map), and rounding outwards can only add
//! area: a person who is really sitting at a table can never fall outside the
//! rectangle. A person inside the rectangle but at no table is still reported
//! as outside every footprint by `spatial_engine::multi_table`.

use crate::loader::TableConfigSet;
use crate::polygon::Point;

/// Rectangle around every mapped table footprint, in normalised `0..=1` space.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AnalysisRegion {
    /// Left edge as a fraction of the frame width.
    pub x: f64,
    /// Top edge as a fraction of the frame height.
    pub y: f64,
    /// Width as a fraction of the frame width.
    pub width: f64,
    /// Height as a fraction of the frame height.
    pub height: f64,
}

impl AnalysisRegion {
    /// Smallest rectangle containing every point, or `None` for an empty set.
    ///
    /// The result is not filtered: a mapping that covers the whole frame
    /// honestly reports the whole frame, because the user did map all of it.
    pub fn from_points(points: impl IntoIterator<Item = Point>) -> Option<Self> {
        let mut bounds: Option<(f64, f64, f64, f64)> = None;
        for point in points {
            if !point.x.is_finite() || !point.y.is_finite() {
                continue;
            }
            bounds = Some(match bounds {
                None => (point.x, point.y, point.x, point.y),
                Some((left, top, right, bottom)) => (
                    left.min(point.x),
                    top.min(point.y),
                    right.max(point.x),
                    bottom.max(point.y),
                ),
            });
        }

        let (left, top, right, bottom) = bounds?;
        Some(Self {
            x: left.clamp(0.0, 1.0),
            y: top.clamp(0.0, 1.0),
            width: (right - left).clamp(0.0, 1.0),
            height: (bottom - top).clamp(0.0, 1.0),
        })
    }

    /// Rectangle around every footprint of a parsed configuration.
    ///
    /// `None` when the configuration has no tables, which the overlay must draw
    /// as "nothing is being watched" instead of a scope covering the whole
    /// frame.
    pub fn from_config(config: &TableConfigSet) -> Option<Self> {
        Self::from_points(
            config
                .tables
                .iter()
                .flat_map(|table| table.polygon.points().iter().copied()),
        )
    }

    /// Whether a normalised point falls inside the mapped scope.
    ///
    /// Edges count as inside: a person standing exactly on the boundary of the
    /// rectangle is visible in the mapping, and hiding their box would look
    /// like the detector dropped them.
    pub fn contains(&self, point: Point) -> bool {
        point.x >= self.x
            && point.x <= self.x + self.width
            && point.y >= self.y
            && point.y <= self.y + self.height
    }
}

impl TableConfigSet {
    /// Mapped analysis scope, or `None` when no table was configured.
    pub fn analysis_region(&self) -> Option<AnalysisRegion> {
        AnalysisRegion::from_config(self)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TWO_TABLES: &str = r#"{
      "version": 2,
      "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
      "tables": [
        { "id": "meja-01", "polygon": [
            { "x": 0.10, "y": 0.20 }, { "x": 0.30, "y": 0.20 },
            { "x": 0.30, "y": 0.45 }, { "x": 0.10, "y": 0.45 } ] },
        { "id": "meja-02", "polygon": [
            { "x": 0.55, "y": 0.50 }, { "x": 0.80, "y": 0.50 },
            { "x": 0.80, "y": 0.75 }, { "x": 0.55, "y": 0.75 } ] }
      ]
    }"#;

    const ONE_TABLE: &str = r#"{
      "version": 2,
      "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
      "tables": [
        { "id": "meja-01", "polygon": [
            { "x": 0.20, "y": 0.20 }, { "x": 0.40, "y": 0.20 },
            { "x": 0.40, "y": 0.40 }, { "x": 0.20, "y": 0.40 } ] }
      ]
    }"#;

    #[test]
    fn the_region_covers_every_mapped_table() {
        let config = TableConfigSet::from_json(TWO_TABLES).unwrap();
        let region = config.analysis_region().unwrap();

        assert!((region.x - 0.10).abs() < 1e-9);
        assert!((region.y - 0.20).abs() < 1e-9);
        assert!((region.width - 0.70).abs() < 1e-9);
        assert!((region.height - 0.55).abs() < 1e-9);
        for table in &config.tables {
            for point in table.polygon.points() {
                assert!(
                    region.contains(*point),
                    "every mapped vertex must be inside the drawn scope"
                );
            }
        }
    }

    #[test]
    fn a_person_between_two_far_tables_is_inside_the_scope_but_at_no_table() {
        let config = TableConfigSet::from_json(TWO_TABLES).unwrap();
        let region = config.analysis_region().unwrap();
        let between = Point::new(0.45, 0.30);

        assert!(region.contains(between));
        assert!(
            config
                .tables
                .iter()
                .all(|table| !table.polygon.contains(between)),
            "the scope is not a table: this person must not occupy one"
        );
    }

    #[test]
    fn a_person_outside_the_mapped_scope_is_not_inside_it() {
        let config = TableConfigSet::from_json(ONE_TABLE).unwrap();
        let region = config.analysis_region().unwrap();

        assert!(!region.contains(Point::new(0.05, 0.15)));
        assert!(!region.contains(Point::new(0.90, 0.90)));
        assert!(
            region.contains(Point::new(0.20, 0.20)),
            "an edge of the mapping is still part of it"
        );
    }

    #[test]
    fn a_configuration_without_tables_has_no_scope() {
        let config = TableConfigSet::from_json(
            r#"{ "version": 2, "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
                 "tables": [] }"#,
        )
        .expect("an empty table list is a valid configuration");

        assert!(config.analysis_region().is_none());
    }

    #[test]
    fn a_full_frame_mapping_reports_the_full_frame() {
        let region = AnalysisRegion::from_points(vec![
            Point::new(0.0, 0.0),
            Point::new(1.0, 0.0),
            Point::new(1.0, 1.0),
            Point::new(0.0, 1.0),
        ])
        .unwrap();

        assert_eq!(
            region,
            AnalysisRegion {
                x: 0.0,
                y: 0.0,
                width: 1.0,
                height: 1.0
            }
        );
        assert!(region.contains(Point::new(0.5, 0.5)));
    }
}
