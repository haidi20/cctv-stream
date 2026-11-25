//! Non-maximum suppression over person boxes.

/// Axis-aligned box in source-frame pixels.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BoxRect {
    /// Left edge.
    pub x: f32,
    /// Top edge.
    pub y: f32,
    /// Width.
    pub width: f32,
    /// Height.
    pub height: f32,
    /// Model confidence.
    pub confidence: f32,
}

impl BoxRect {
    /// Area, clamped at zero for degenerate boxes.
    pub fn area(&self) -> f32 {
        self.width.max(0.0) * self.height.max(0.0)
    }
}

/// Intersection over union for two boxes.
pub fn iou(a: BoxRect, b: BoxRect) -> f32 {
    let left = a.x.max(b.x);
    let top = a.y.max(b.y);
    let right = (a.x + a.width).min(b.x + b.width);
    let bottom = (a.y + a.height).min(b.y + b.height);
    let width = (right - left).max(0.0);
    let height = (bottom - top).max(0.0);
    let intersection = width * height;
    let union = a.area() + b.area() - intersection;
    if union <= 0.0 {
        0.0
    } else {
        intersection / union
    }
}

/// Greedy suppression by confidence.
///
/// Input order is irrelevant; the output keeps the highest-confidence box of
/// each cluster, sorted by confidence descending.
pub fn suppress(mut boxes: Vec<BoxRect>, threshold: f32) -> Vec<BoxRect> {
    boxes.sort_by(|left, right| {
        right
            .confidence
            .partial_cmp(&left.confidence)
            .unwrap_or(std::cmp::Ordering::Equal)
    });

    let mut kept: Vec<BoxRect> = Vec::with_capacity(boxes.len());
    for candidate in boxes {
        if kept.iter().all(|kept| iou(*kept, candidate) <= threshold) {
            kept.push(candidate);
        }
    }
    kept
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rect(x: f32, y: f32, width: f32, height: f32, confidence: f32) -> BoxRect {
        BoxRect {
            x,
            y,
            width,
            height,
            confidence,
        }
    }

    #[test]
    fn identical_boxes_keep_the_most_confident() {
        let kept = suppress(
            vec![
                rect(0.0, 0.0, 10.0, 10.0, 0.5),
                rect(0.0, 0.0, 10.0, 10.0, 0.9),
            ],
            0.5,
        );
        assert_eq!(kept.len(), 1);
        assert!((kept[0].confidence - 0.9).abs() < 1e-6);
    }

    #[test]
    fn separate_boxes_survive() {
        let kept = suppress(
            vec![
                rect(0.0, 0.0, 10.0, 10.0, 0.8),
                rect(100.0, 100.0, 10.0, 10.0, 0.7),
            ],
            0.5,
        );
        assert_eq!(kept.len(), 2);
    }

    #[test]
    fn overlapping_boxes_above_threshold_are_merged() {
        let kept = suppress(
            vec![
                rect(0.0, 0.0, 10.0, 10.0, 0.9),
                rect(1.0, 1.0, 10.0, 10.0, 0.6),
            ],
            0.4,
        );
        assert_eq!(kept.len(), 1);
    }

    #[test]
    fn iou_of_disjoint_boxes_is_zero() {
        assert_eq!(
            iou(
                rect(0.0, 0.0, 5.0, 5.0, 1.0),
                rect(10.0, 10.0, 5.0, 5.0, 1.0)
            ),
            0.0
        );
    }
}
