//! Centroid helpers for detections.

use crate::geometry::{centroid_normalized, FrameSize, PixelBox};
use crate::polygon::Point;

/// A single person detection in frame pixels.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Detection {
    /// Bounding box in frame pixels.
    pub bbox: PixelBox,
    /// Model confidence, `0..=1`.
    pub confidence: f64,
}

impl Detection {
    /// Build a detection.
    pub fn new(bbox: PixelBox, confidence: f64) -> Self {
        Self { bbox, confidence }
    }

    /// Normalised centroid, clamped to the frame.
    pub fn centroid(&self, frame: FrameSize) -> Point {
        centroid_normalized(self.bbox, frame)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_size_box_maps_to_its_origin() {
        let detection = Detection::new(PixelBox::new(384.0, 216.0, 0.0, 0.0), 0.9);
        let frame = FrameSize::new(1280, 720).unwrap();
        let point = detection.centroid(frame);
        assert!((point.x - 0.3).abs() < 1e-9);
        assert!((point.y - 0.3).abs() < 1e-9);
    }

    #[test]
    fn box_past_the_edge_is_clamped() {
        let detection = Detection::new(PixelBox::new(1270.0, 710.0, 100.0, 100.0), 0.8);
        let frame = FrameSize::new(1280, 720).unwrap();
        let point = detection.centroid(frame);
        assert_eq!(point.x, 1.0);
        assert_eq!(point.y, 1.0);
    }
}
