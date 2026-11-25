//! Table configuration loader.

use std::collections::HashSet;

use serde::{Deserialize, Serialize};

use crate::error::ConfigError;
use crate::geometry::{AspectRatio, FrameSize};
use crate::polygon::{Point, Polygon, MIN_POLYGON_AREA};

/// Current schema version for normalised coordinates.
pub const CURRENT_VERSION: u32 = 2;

#[derive(Debug, Deserialize)]
struct RawConfig {
    version: u32,
    video: RawVideo,
    tables: Vec<RawTable>,
}

#[derive(Debug, Deserialize)]
struct RawVideo {
    width: u32,
    height: u32,
    aspect_ratio: String,
}

#[derive(Debug, Deserialize)]
struct RawTable {
    id: String,
    #[serde(default)]
    label: Option<String>,
    polygon: Vec<RawPoint>,
}

#[derive(Debug, Deserialize)]
struct RawPoint {
    x: f64,
    y: f64,
}

/// Validated table definition.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct TableConfig {
    /// Stable identifier used by UI and server.
    pub id: String,
    /// Human readable label; falls back to the id.
    pub label: String,
    /// Validated footprint.
    #[serde(skip)]
    pub polygon: Polygon,
}

impl TableConfig {
    /// Serialisable polygon points.
    pub fn points(&self) -> &[Point] {
        self.polygon.points()
    }
}

/// A fully validated configuration file.
#[derive(Debug, Clone, PartialEq)]
pub struct TableConfigSet {
    /// Reference frame recorded with the polygons.
    pub frame: FrameSize,
    /// Aspect ratio recorded with the polygons.
    pub aspect_ratio: AspectRatio,
    /// Tables in file order.
    pub tables: Vec<TableConfig>,
}

impl TableConfigSet {
    /// Parse and validate JSON.
    pub fn from_json(input: &str) -> Result<Self, ConfigError> {
        let raw: RawConfig =
            serde_json::from_str(input).map_err(|error| ConfigError::Json(error.to_string()))?;

        if raw.version == 1 {
            return Err(ConfigError::LegacyPixelVersion);
        }
        if raw.version != CURRENT_VERSION {
            return Err(ConfigError::UnsupportedVersion {
                version: raw.version,
            });
        }

        let frame = FrameSize::new(raw.video.width, raw.video.height)
            .ok_or(ConfigError::InvalidFrameSize)?;
        let aspect_ratio = AspectRatio::parse(&raw.video.aspect_ratio)?;

        let mut seen = HashSet::new();
        let mut tables = Vec::with_capacity(raw.tables.len());

        for table in raw.tables {
            let id = table.id.trim().to_string();
            if id.is_empty() {
                return Err(ConfigError::EmptyId);
            }
            if !seen.insert(id.clone()) {
                return Err(ConfigError::DuplicateId { table_id: id });
            }
            if table.polygon.len() < 3 {
                return Err(ConfigError::TooFewPoints {
                    table_id: id,
                    points: table.polygon.len(),
                });
            }

            let points: Vec<Point> = table
                .polygon
                .iter()
                .map(|point| Point::new(point.x, point.y))
                .collect();

            if points.iter().any(|p| {
                !p.x.is_finite()
                    || !p.y.is_finite()
                    || !(0.0..=1.0).contains(&p.x)
                    || !(0.0..=1.0).contains(&p.y)
            }) {
                return Err(ConfigError::CoordinateOutOfRange { table_id: id });
            }

            let polygon = Polygon::new(points).ok_or_else(|| {
                if polygon_area(&table.polygon) < MIN_POLYGON_AREA {
                    ConfigError::DegenerateArea {
                        table_id: id.clone(),
                        area: polygon_area(&table.polygon),
                    }
                } else {
                    ConfigError::SelfIntersecting {
                        table_id: id.clone(),
                    }
                }
            })?;

            let label = table.label.unwrap_or_else(|| id.clone());
            tables.push(TableConfig { id, label, polygon });
        }

        Ok(Self {
            frame,
            aspect_ratio,
            tables,
        })
    }

    /// Table lookup by id.
    pub fn table(&self, id: &str) -> Option<&TableConfig> {
        self.tables.iter().find(|table| table.id == id)
    }

    /// Number of configured tables.
    pub fn len(&self) -> usize {
        self.tables.len()
    }

    /// True when no table is configured.
    pub fn is_empty(&self) -> bool {
        self.tables.is_empty()
    }
}

fn polygon_area(points: &[RawPoint]) -> f64 {
    let count = points.len();
    if count < 3 {
        return 0.0;
    }

    let mut sum = 0.0;
    for index in 0..count {
        let a = &points[index];
        let b = &points[(index + 1) % count];
        sum += a.x * b.y - b.x * a.y;
    }
    (sum / 2.0).abs()
}

#[cfg(test)]
mod tests {
    use super::*;

    const VALID: &str = include_str!("../../../contracts/fixtures/valid/meja-mock.json");

    #[test]
    fn loads_the_canonical_fixture() {
        let config = TableConfigSet::from_json(VALID).unwrap();
        assert_eq!(config.len(), 1);
        assert_eq!(config.frame, FrameSize::new(1280, 720).unwrap());
        assert_eq!(config.aspect_ratio.as_f64(), 16.0 / 9.0);

        let table = config.table("meja-mock").unwrap();
        assert_eq!(table.points().len(), 4);
    }

    #[test]
    fn rejects_version_one_with_a_migration_message() {
        let json = VALID.replace("\"version\": 2", "\"version\": 1");
        assert_eq!(
            TableConfigSet::from_json(&json),
            Err(ConfigError::LegacyPixelVersion)
        );
    }

    #[test]
    fn rejects_unknown_version() {
        let json = VALID.replace("\"version\": 2", "\"version\": 9");
        assert_eq!(
            TableConfigSet::from_json(&json),
            Err(ConfigError::UnsupportedVersion { version: 9 })
        );
    }

    #[test]
    fn rejects_duplicate_ids() {
        let json = r#"{
          "version": 2,
          "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
          "tables": [
            { "id": "a", "polygon": [{ "x": 0.1, "y": 0.1 }, { "x": 0.3, "y": 0.1 }, { "x": 0.3, "y": 0.3 }] },
            { "id": "a", "polygon": [{ "x": 0.4, "y": 0.4 }, { "x": 0.6, "y": 0.4 }, { "x": 0.6, "y": 0.6 }] }
          ]
        }"#;

        assert_eq!(
            TableConfigSet::from_json(json),
            Err(ConfigError::DuplicateId {
                table_id: "a".to_string()
            })
        );
    }

    #[test]
    fn rejects_coordinates_outside_the_unit_square() {
        let json = VALID.replace("\"x\": 0.2", "\"x\": 1.2");
        assert!(matches!(
            TableConfigSet::from_json(&json),
            Err(ConfigError::CoordinateOutOfRange { .. })
        ));
    }

    #[test]
    fn rejects_too_few_points() {
        let json = r#"{
          "version": 2,
          "video": { "width": 1280, "height": 720, "aspect_ratio": "16:9" },
          "tables": [
            { "id": "a", "polygon": [{ "x": 0.1, "y": 0.1 }, { "x": 0.3, "y": 0.3 }] }
          ]
        }"#;
        assert_eq!(
            TableConfigSet::from_json(json),
            Err(ConfigError::TooFewPoints {
                table_id: "a".to_string(),
                points: 2
            })
        );
    }

    #[test]
    fn rejects_degenerate_area() {
        let json = VALID.replace("\"x\": 0.4", "\"x\": 0.2001");
        assert!(matches!(
            TableConfigSet::from_json(&json),
            Err(ConfigError::DegenerateArea { .. })
        ));
    }
}
