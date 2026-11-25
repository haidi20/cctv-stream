//! Errors produced while loading table configuration.

use thiserror::Error;

/// Why a configuration was rejected.
#[derive(Debug, Clone, PartialEq, Error)]
pub enum ConfigError {
    /// JSON was syntactically invalid or had the wrong shape.
    #[error("invalid json: {0}")]
    Json(String),
    /// `version: 1` used pixel coordinates and must be migrated by hand.
    #[error("config version 1 uses pixel coordinates; migration is required")]
    LegacyPixelVersion,
    /// Schema version is unknown.
    #[error("unsupported config version {version}")]
    UnsupportedVersion {
        /// Version found in the file.
        version: u32,
    },
    /// A polygon has fewer than three points.
    #[error("table '{table_id}' has {points} points; at least 3 are required")]
    TooFewPoints {
        /// Table identifier.
        table_id: String,
        /// Number of points found.
        points: usize,
    },
    /// A polygon edge crosses another edge.
    #[error("table '{table_id}' has a self-intersecting polygon")]
    SelfIntersecting {
        /// Table identifier.
        table_id: String,
    },
    /// Coordinates must be finite and inside `[0, 1]`.
    #[error("table '{table_id}' has a coordinate outside [0, 1]")]
    CoordinateOutOfRange {
        /// Table identifier.
        table_id: String,
    },
    /// Polygon area is below the minimum.
    #[error("table '{table_id}' has area {area:.6}, below the minimum of 0.0005")]
    DegenerateArea {
        /// Table identifier.
        table_id: String,
        /// Computed absolute area.
        area: f64,
    },
    /// Two tables share an identifier.
    #[error("duplicate table id '{table_id}'")]
    DuplicateId {
        /// Table identifier.
        table_id: String,
    },
    /// Table identifier is empty.
    #[error("table id must not be empty")]
    EmptyId,
    /// Aspect ratio string was not `W:H`.
    #[error("invalid aspect ratio '{value}'")]
    InvalidAspectRatio {
        /// Rejected value.
        value: String,
    },
    /// Reference frame dimensions must be positive.
    #[error("video width and height must be greater than zero")]
    InvalidFrameSize,
}
