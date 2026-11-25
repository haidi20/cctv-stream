//! Conversion between frame pixels and normalised coordinates.

use crate::error::ConfigError;
use crate::polygon::Point;

/// Stream frame dimensions and aspect ratio.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameSize {
    /// Width in pixels.
    pub width: u32,
    /// Height in pixels.
    pub height: u32,
}

impl FrameSize {
    /// Construct a frame size, rejecting zero dimensions.
    pub fn new(width: u32, height: u32) -> Option<Self> {
        if width == 0 || height == 0 {
            return None;
        }
        Some(Self { width, height })
    }

    /// Width divided by height.
    pub fn aspect_ratio(&self) -> f64 {
        f64::from(self.width) / f64::from(self.height)
    }
}

/// Aspect ratio in `W:H` form, used by the configuration contract.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AspectRatio {
    numerator: u32,
    denominator: u32,
}

impl AspectRatio {
    /// Parse `"16:9"`.
    pub fn parse(value: &str) -> Result<Self, ConfigError> {
        let (left, right) =
            value
                .split_once(':')
                .ok_or_else(|| ConfigError::InvalidAspectRatio {
                    value: value.to_string(),
                })?;

        let numerator: u32 = left
            .trim()
            .parse()
            .map_err(|_| ConfigError::InvalidAspectRatio {
                value: value.to_string(),
            })?;
        let denominator: u32 =
            right
                .trim()
                .parse()
                .map_err(|_| ConfigError::InvalidAspectRatio {
                    value: value.to_string(),
                })?;

        if numerator == 0 || denominator == 0 {
            return Err(ConfigError::InvalidAspectRatio {
                value: value.to_string(),
            });
        }

        Ok(Self {
            numerator,
            denominator,
        })
    }

    /// Numeric ratio.
    pub fn as_f64(self) -> f64 {
        f64::from(self.numerator) / f64::from(self.denominator)
    }

    /// Whether the frame is an exact multiple of this ratio.
    ///
    /// Comparison is done by cross-multiplication instead of floating point so
    /// that anamorphic resolutions are not silently accepted. `854x480` is
    /// within 0.08% of `16:9` but its pixels are not square; ADR 0008 requires
    /// a recalibration prompt instead of guessing (see [`needs_recalibration`]).
    pub fn matches_frame(self, frame: FrameSize) -> bool {
        u64::from(self.numerator) * u64::from(frame.height)
            == u64::from(self.denominator) * u64::from(frame.width)
    }
}

/// Convert a pixel coordinate to `0..=1`, clamping points outside the frame.
pub fn to_normalized(pixel: (f64, f64), frame: FrameSize) -> Point {
    Point::new(
        (pixel.0 / f64::from(frame.width)).clamp(0.0, 1.0),
        (pixel.1 / f64::from(frame.height)).clamp(0.0, 1.0),
    )
}

/// Bounding box in frame pixels.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PixelBox {
    /// Left edge.
    pub x: f64,
    /// Top edge.
    pub y: f64,
    /// Box width.
    pub width: f64,
    /// Box height.
    pub height: f64,
}

impl PixelBox {
    /// Build a box.
    pub fn new(x: f64, y: f64, width: f64, height: f64) -> Self {
        Self {
            x,
            y,
            width,
            height,
        }
    }

    /// Center of the box in frame pixels.
    pub fn center(&self) -> (f64, f64) {
        (self.x + self.width / 2.0, self.y + self.height / 2.0)
    }
}

/// Center of a bounding box in normalised coordinates.
///
/// Boxes that extend past the frame edge are clamped rather than rejected, so a
/// person walking out of view still maps to a valid point.
pub fn centroid_normalized(detection: PixelBox, frame: FrameSize) -> Point {
    to_normalized(detection.center(), frame)
}

/// Whether a configured aspect ratio still matches the live stream.
pub fn needs_recalibration(configured: AspectRatio, frame: FrameSize) -> bool {
    !configured.matches_frame(frame)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame_720p() -> FrameSize {
        FrameSize::new(1280, 720).unwrap()
    }

    #[test]
    fn rejects_zero_dimensions() {
        assert!(FrameSize::new(0, 720).is_none());
        assert!(FrameSize::new(1280, 0).is_none());
    }

    #[test]
    fn converts_pixels_to_normalized() {
        let point = to_normalized((384.0, 216.0), frame_720p());
        assert!((point.x - 0.3).abs() < 1e-9);
        assert!((point.y - 0.3).abs() < 1e-9);
    }

    #[test]
    fn clamps_points_outside_the_frame() {
        let point = to_normalized((-50.0, 2000.0), frame_720p());
        assert_eq!(point.x, 0.0);
        assert_eq!(point.y, 1.0);
    }

    #[test]
    fn box_center_maps_to_expected_fraction() {
        let point = centroid_normalized(PixelBox::new(1000.0, 500.0, 0.0, 0.0), frame_720p());
        assert!((point.x - 0.78125).abs() < 1e-9);
        assert!((point.y - 0.694_444_444).abs() < 1e-9);
    }

    #[test]
    fn same_aspect_ratio_survives_resolution_change() {
        let configured = AspectRatio::parse("16:9").unwrap();
        assert!(!needs_recalibration(
            configured,
            FrameSize::new(960, 540).unwrap()
        ));
        assert!(!needs_recalibration(
            configured,
            FrameSize::new(768, 432).unwrap()
        ));
    }

    #[test]
    fn aspect_change_requires_recalibration() {
        let configured = AspectRatio::parse("16:9").unwrap();
        assert!(needs_recalibration(
            configured,
            FrameSize::new(1024, 768).unwrap()
        ));
    }

    #[test]
    fn rejects_malformed_aspect_ratio() {
        assert!(AspectRatio::parse("16x9").is_err());
        assert!(AspectRatio::parse("16:0").is_err());
        assert!(AspectRatio::parse(":9").is_err());
    }
}
