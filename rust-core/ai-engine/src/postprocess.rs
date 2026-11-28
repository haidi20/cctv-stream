//! Decode YOLO person predictions into source-frame boxes.
//!
//! Supported layouts are the two exported by Ultralytics:
//!
//! - `[1, 84, 8400]` - attribute-major (v8/v11 default export)
//! - `[1, 8400, 84]` - prediction-major (some NMS-free exports)
//!
//! Prediction-major tensors are decoded directly. Attribute-major tensors are
//! decoded in place with a stride, which avoids a full transpose allocation on
//! every frame. Only the `person` class (COCO index 0) is kept; the rest of
//! the 80 classes are ignored because table occupancy only cares about people.
//!
//! A box that survives the confidence floor is not automatically a person. At
//! 2 FPS a detector happily emits a 9x18 px smudge on a chair leg or a wide
//! horizontal smear on a table edge, and the overlay draws both. [`BoxFilter`]
//! drops those before NMS, in source-frame pixels, so the drawn boxes and the
//! table counts are built from the same set (`docs/detection-quality-todo.md`
//! Q-06).

use crate::error::AiError;
use crate::nms::{suppress, BoxRect};
use crate::preprocess::Letterbox;

/// Number of values per prediction in a YOLOv8/v11 export: 4 box + 80 classes.
pub const VALUES_PER_PREDICTION: usize = 84;
/// Size of the smallest usable YOLO output: exactly one prediction.
pub const MIN_OUTPUT_VALUES: usize = VALUES_PER_PREDICTION;

/// Decoded detections in source-frame pixels.
#[derive(Debug, Clone, PartialEq)]
pub struct Detections {
    /// Person boxes after NMS.
    pub boxes: Vec<BoxRect>,
}

impl Detections {
    /// No person detected.
    pub fn empty() -> Self {
        Self { boxes: Vec::new() }
    }
}

/// Default smallest side of a person box, in source-frame pixels.
///
/// At 640 wide, a person sitting at the far end of a warung is 60-120 px tall.
/// A 12 px side is roughly a head at the back wall: real enough to count, and
/// still above the noise a detector emits on furniture edges.
pub const DEFAULT_MIN_SIDE_PX: f32 = 12.0;

/// Narrowest plausible person box, as `height / width`.
///
/// A person seated sideways and leaning over a table can be wider than tall,
/// but not by more than roughly three to one. Below this a box is a horizontal
/// smear, usually a table edge or a bright reflection.
pub const MIN_ASPECT: f32 = 0.35;

/// Tallest plausible person box, as `height / width`.
///
/// A standing person seen from the floor is about 4:1. Beyond 8:1 the box is a
/// door frame or a curtain fold, not a body.
pub const MAX_ASPECT: f32 = 8.0;

/// Plausibility filter applied to decoded boxes before NMS.
///
/// This lives in the pure decode step, not in the UI, so the boxes drawn on the
/// screen and the boxes counted per table are always the same set: filtering in
/// Kotlin would let a rejected box still mark a table occupied.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BoxFilter {
    /// Smallest accepted side (`min(width, height)`), in source-frame pixels.
    pub min_side_px: f32,
    /// Smallest accepted `height / width`.
    pub min_aspect: f32,
    /// Largest accepted `height / width`.
    pub max_aspect: f32,
}

impl Default for BoxFilter {
    fn default() -> Self {
        Self {
            min_side_px: DEFAULT_MIN_SIDE_PX,
            min_aspect: MIN_ASPECT,
            max_aspect: MAX_ASPECT,
        }
    }
}

impl BoxFilter {
    /// Filter that accepts every non-degenerate box.
    ///
    /// Used by callers that want the raw decoder, and by the tests that prove
    /// the filter is what removes a box rather than the decoder.
    pub fn permissive() -> Self {
        Self {
            min_side_px: 0.0,
            min_aspect: 0.0,
            max_aspect: f32::INFINITY,
        }
    }

    /// Whether [candidate] is plausible enough to draw and to count.
    ///
    /// A degenerate box (zero or negative side) is always rejected: it has no
    /// area to test against a polygon, and it would divide by zero below.
    pub fn accepts(&self, candidate: &BoxRect) -> bool {
        let (width, height) = (candidate.width, candidate.height);
        if !(width.is_finite() && height.is_finite()) || width <= 0.0 || height <= 0.0 {
            return false;
        }
        if width.min(height) < self.min_side_px {
            return false;
        }
        let aspect = height / width;
        aspect >= self.min_aspect && aspect <= self.max_aspect
    }

    /// Short, frame-free reason for a rejection, for `tracing::debug!`.
    ///
    /// Only called on the reject path, so it never allocates on a frame where
    /// every box is plausible.
    pub fn rejection_reason(&self, candidate: &BoxRect) -> &'static str {
        let (width, height) = (candidate.width, candidate.height);
        if !(width.is_finite() && height.is_finite()) || width <= 0.0 || height <= 0.0 {
            return "degenerate";
        }
        if width.min(height) < self.min_side_px {
            return "too_small";
        }
        "extreme_aspect"
    }
}

/// Shape of the raw model output.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OutputShape {
    /// Raw tensor length.
    pub len: usize,
}

impl OutputShape {
    /// Infer the prediction count from a flat tensor length.
    pub fn predictions(len: usize) -> Result<usize, AiError> {
        if len < MIN_OUTPUT_VALUES || !len.is_multiple_of(VALUES_PER_PREDICTION) {
            return Err(AiError::Output {
                reason: format!("expected a multiple of {VALUES_PER_PREDICTION} values, got {len}"),
            });
        }
        Ok(len / VALUES_PER_PREDICTION)
    }
}

/// Decode raw YOLO output into person boxes in source-frame pixels.
///
/// Boxes that are too small or too oddly shaped to be a person are dropped
/// before NMS by [`BoxFilter::default`]. Use [`decode_with_filter`] to change
/// the bounds, for example when tuning against recorded footage.
pub fn decode(
    raw: &[f32],
    letterbox: Letterbox,
    source_width: u32,
    source_height: u32,
    confidence_threshold: f32,
    iou_threshold: f32,
) -> Result<Detections, AiError> {
    decode_with_filter(
        raw,
        letterbox,
        source_width,
        source_height,
        confidence_threshold,
        iou_threshold,
        BoxFilter::default(),
    )
}

/// Decode raw YOLO output, drop implausible boxes, then remove duplicates.
pub fn decode_with_filter(
    raw: &[f32],
    letterbox: Letterbox,
    source_width: u32,
    source_height: u32,
    confidence_threshold: f32,
    iou_threshold: f32,
    filter: BoxFilter,
) -> Result<Detections, AiError> {
    let predictions = OutputShape::predictions(raw.len())?;
    let mut boxes = Vec::new();

    for index in 0..predictions {
        let confidence = raw[index * VALUES_PER_PREDICTION + 4];
        if !(confidence.is_finite() && confidence >= confidence_threshold) {
            continue;
        }

        let cx = raw[index * VALUES_PER_PREDICTION];
        let cy = raw[index * VALUES_PER_PREDICTION + 1];
        let width = raw[index * VALUES_PER_PREDICTION + 2];
        let height = raw[index * VALUES_PER_PREDICTION + 3];
        if !(cx.is_finite() && cy.is_finite() && width.is_finite() && height.is_finite()) {
            continue;
        }

        let (left, top) = letterbox.to_source(cx - width / 2.0, cy - height / 2.0);
        let (right, bottom) = letterbox.to_source(cx + width / 2.0, cy + height / 2.0);
        let left = left.clamp(0.0, source_width as f32);
        let top = top.clamp(0.0, source_height as f32);
        let right = right.clamp(0.0, source_width as f32);
        let bottom = bottom.clamp(0.0, source_height as f32);
        if right <= left || bottom <= top {
            continue;
        }

        boxes.push(BoxRect {
            x: left,
            y: top,
            width: right - left,
            height: bottom - top,
            confidence,
        });
    }

    Ok(finish(boxes, iou_threshold, filter))
}

/// Decode a `[1, attributes, predictions]` tensor without transposing it.
///
/// Equivalent to [`transpose_attribute_major`] followed by [`decode`], but it
/// reads the raw buffer with a stride so a 640x640 export does not allocate a
/// second copy of the whole tensor on every frame.
pub fn decode_attribute_major(
    raw: &[f32],
    letterbox: Letterbox,
    source_width: u32,
    source_height: u32,
    confidence_threshold: f32,
    iou_threshold: f32,
) -> Result<Detections, AiError> {
    decode_attribute_major_with_filter(
        raw,
        letterbox,
        source_width,
        source_height,
        confidence_threshold,
        iou_threshold,
        BoxFilter::default(),
    )
}

/// Strided attribute-major decode with an explicit [`BoxFilter`].
pub fn decode_attribute_major_with_filter(
    raw: &[f32],
    letterbox: Letterbox,
    source_width: u32,
    source_height: u32,
    confidence_threshold: f32,
    iou_threshold: f32,
    filter: BoxFilter,
) -> Result<Detections, AiError> {
    let predictions = OutputShape::predictions(raw.len())?;
    let mut boxes = Vec::new();

    // Scan only the contiguous class-0 confidence row. Reading the three
    // strided coordinate rows for all 8400 anchors used to touch ~2.8 MB of
    // mostly-rejected predictions per frame; with a 0.35 floor only a handful
    // of anchors survive, so their rows are loaded after the cheap filter.
    let confidence_row = &raw[4 * predictions..5 * predictions];
    for (index, &confidence) in confidence_row.iter().enumerate() {
        if !(confidence.is_finite() && confidence >= confidence_threshold) {
            continue;
        }

        let cx = raw[index];
        let cy = raw[predictions + index];
        let width = raw[2 * predictions + index];
        let height = raw[3 * predictions + index];
        if !(cx.is_finite() && cy.is_finite() && width.is_finite() && height.is_finite()) {
            continue;
        }

        let (left, top) = letterbox.to_source(cx - width / 2.0, cy - height / 2.0);
        let (right, bottom) = letterbox.to_source(cx + width / 2.0, cy + height / 2.0);
        let left = left.clamp(0.0, source_width as f32);
        let top = top.clamp(0.0, source_height as f32);
        let right = right.clamp(0.0, source_width as f32);
        let bottom = bottom.clamp(0.0, source_height as f32);
        if right <= left || bottom <= top {
            continue;
        }

        boxes.push(BoxRect {
            x: left,
            y: top,
            width: right - left,
            height: bottom - top,
            confidence,
        });
    }

    Ok(finish(boxes, iou_threshold, filter))
}

/// Drop implausible boxes, then merge overlapping ones.
///
/// Filtering happens before NMS on purpose: a rejected smudge must not win a
/// suppression contest against a real person, and the surviving set is what
/// both the overlay and the per-table counts are built from.
fn finish(mut boxes: Vec<BoxRect>, iou_threshold: f32, filter: BoxFilter) -> Detections {
    boxes.retain(|candidate| {
        let keep = filter.accepts(candidate);
        if !keep {
            tracing::debug!(
                width = candidate.width,
                height = candidate.height,
                confidence = candidate.confidence,
                reason = filter.rejection_reason(candidate),
                "dropping implausible person box"
            );
        }
        keep
    });
    Detections {
        boxes: suppress(boxes, iou_threshold),
    }
}

/// Convert a `[1, attributes, predictions]` tensor into prediction-major.
pub fn transpose_attribute_major(raw: &[f32]) -> Result<Vec<f32>, AiError> {
    let predictions = OutputShape::predictions(raw.len())?;
    let mut out = vec![0.0f32; raw.len()];
    for attribute in 0..VALUES_PER_PREDICTION {
        for prediction in 0..predictions {
            out[prediction * VALUES_PER_PREDICTION + attribute] =
                raw[attribute * predictions + prediction];
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::preprocess::Letterbox;

    fn identity(size: u32) -> Letterbox {
        Letterbox {
            scale: 1.0,
            pad_x: 0.0,
            pad_y: 0.0,
            size,
        }
    }

    fn row(cx: f32, cy: f32, width: f32, height: f32, confidence: f32) -> Vec<f32> {
        let mut values = vec![0.0f32; VALUES_PER_PREDICTION];
        values[0] = cx;
        values[1] = cy;
        values[2] = width;
        values[3] = height;
        values[4] = confidence;
        values
    }

    #[test]
    fn rejects_tensor_with_the_wrong_stride() {
        let error = decode(&[0.0; 10], identity(640), 640, 480, 0.3, 0.5).unwrap_err();
        assert!(matches!(error, AiError::Output { .. }));
    }

    #[test]
    fn keeps_only_confident_people() {
        let mut raw = row(100.0, 100.0, 40.0, 80.0, 0.9);
        raw.extend(row(300.0, 200.0, 40.0, 80.0, 0.1));
        let result = decode(&raw, identity(640), 640, 480, 0.35, 0.5).unwrap();
        assert_eq!(result.boxes.len(), 1);
        assert!((result.boxes[0].x - 80.0).abs() < 1e-4);
        assert!((result.boxes[0].y - 60.0).abs() < 1e-4);
    }

    #[test]
    fn boxes_are_clamped_to_the_frame() {
        let raw = row(10.0, 10.0, 100.0, 100.0, 0.9);
        let result = decode(&raw, identity(640), 640, 480, 0.3, 0.5).unwrap();
        assert!(result.boxes[0].x >= 0.0);
        assert!(result.boxes[0].y >= 0.0);
    }

    #[test]
    fn a_detection_on_the_clip_640_letterbox_maps_back_to_768x432() {
        // 768x432 into a 640x640 square: scale 5/6, vertical padding 140 px.
        // A person's head near (0.16, 0.64) in frame space lands at this
        // model-space centre; decoding must undo both padding and scale.
        let letterbox = Letterbox {
            scale: 5.0 / 6.0,
            pad_x: 0.0,
            pad_y: 140.0,
            size: 640,
        };
        let box_width = 40.0f32 * letterbox.scale;
        let box_height = 80.0f32 * letterbox.scale;
        let raw = row(
            letterbox.pad_x + 0.16 * 768.0 * letterbox.scale,
            letterbox.pad_y + 0.64 * 432.0 * letterbox.scale,
            box_width,
            box_height,
            0.9,
        );
        let result = decode(&raw, letterbox, 768, 432, 0.35, 0.5).unwrap();
        let detected = &result.boxes[0];
        assert!((detected.x - (0.16 * 768.0 - 20.0)).abs() < 1e-2);
        assert!((detected.y - (0.64 * 432.0 - 40.0)).abs() < 1e-2);
        assert!((detected.width - box_width / letterbox.scale).abs() < 1e-2);
        assert!((detected.height - box_height / letterbox.scale).abs() < 1e-2);
    }
    #[test]
    fn letterbox_padding_is_removed() {
        let letterbox = Letterbox {
            scale: 1.0,
            pad_x: 0.0,
            pad_y: 80.0,
            size: 640,
        };
        let raw = row(320.0, 280.0, 40.0, 80.0, 0.9);
        let result = decode(&raw, letterbox, 640, 480, 0.3, 0.5).unwrap();
        assert!((result.boxes[0].y - 160.0).abs() < 1e-3);
    }

    #[test]
    fn attribute_major_tensor_is_transposed() {
        let predictions = 2;
        let mut raw = vec![0.0f32; predictions * VALUES_PER_PREDICTION];
        raw[0] = 111.0;
        raw[1] = 222.0;
        let out = transpose_attribute_major(&raw).unwrap();
        assert!((out[0] - 111.0).abs() < 1e-6);
        assert!((out[VALUES_PER_PREDICTION] - 222.0).abs() < 1e-6);
    }

    #[test]
    fn strided_decode_matches_transpose_then_decode() {
        let predictions = 3;
        let mut raw = vec![0.0f32; predictions * VALUES_PER_PREDICTION];
        for index in 0..predictions {
            raw[index] = 100.0 + index as f32 * 10.0;
            raw[predictions + index] = 50.0 + index as f32 * 5.0;
            raw[2 * predictions + index] = 20.0;
            raw[3 * predictions + index] = 40.0;
        }
        raw[4 * predictions] = 0.9;
        raw[4 * predictions + 1] = 0.2;
        raw[4 * predictions + 2] = 0.8;

        let letterbox = identity(640);
        let strided = decode_attribute_major(&raw, letterbox, 640, 480, 0.35, 0.5).unwrap();
        let transposed = decode(
            &transpose_attribute_major(&raw).unwrap(),
            letterbox,
            640,
            480,
            0.35,
            0.5,
        )
        .unwrap();
        assert_eq!(strided, transposed);
        assert_eq!(strided.boxes.len(), 2);
    }

    #[test]
    fn tiny_box_is_dropped_before_nms() {
        // 6x9 px in a 640-wide frame: the detector's usual noise on a chair
        // leg. It clears the confidence floor, so only the size filter can
        // remove it.
        let raw = row(100.0, 100.0, 6.0, 9.0, 0.9);
        let filtered = decode(&raw, identity(640), 640, 480, 0.35, 0.5).unwrap();
        assert!(filtered.boxes.is_empty());

        let raw_box = decode_with_filter(
            &raw,
            identity(640),
            640,
            480,
            0.35,
            0.5,
            BoxFilter::permissive(),
        )
        .unwrap();
        assert_eq!(raw_box.boxes.len(), 1);
    }

    #[test]
    fn seated_person_box_is_kept() {
        // A person seated at the far table: 60x120 px, aspect 2.0. Real
        // occupancy must survive the filter even though the box is small.
        let raw = row(320.0, 240.0, 60.0, 120.0, 0.72);
        let result = decode(&raw, identity(640), 640, 480, 0.35, 0.5).unwrap();
        assert_eq!(result.boxes.len(), 1);
        assert!((result.boxes[0].confidence - 0.72).abs() < 1e-6);
    }

    #[test]
    fn horizontal_smear_is_rejected() {
        // 200x20 px along a table edge: aspect 0.1, well below a body.
        let raw = row(320.0, 300.0, 200.0, 20.0, 0.8);
        let result = decode(&raw, identity(640), 640, 480, 0.35, 0.5).unwrap();
        assert!(result.boxes.is_empty());
    }

    #[test]
    fn min_box_px_is_configurable() {
        // 16x40 px passes the 12 px default and fails an explicit 20 px floor.
        let raw = row(320.0, 240.0, 16.0, 40.0, 0.9);
        let default = decode(&raw, identity(640), 640, 480, 0.35, 0.5).unwrap();
        assert_eq!(default.boxes.len(), 1);

        let strict = decode_with_filter(
            &raw,
            identity(640),
            640,
            480,
            0.35,
            0.5,
            BoxFilter {
                min_side_px: 20.0,
                ..BoxFilter::default()
            },
        )
        .unwrap();
        assert!(strict.boxes.is_empty());
    }

    #[test]
    fn strided_decode_applies_the_same_filter() {
        // A tiny box and a real one in attribute-major layout: only the real
        // one survives, proving the strided path filters too.
        let predictions = 2;
        let mut raw = vec![0.0f32; predictions * VALUES_PER_PREDICTION];
        // Attribute-major layout: every row holds one attribute for all
        // anchors, so anchor `i` lives at `raw[attribute * predictions + i]`.
        let cx = [100.0f32, 300.0];
        let cy = [100.0f32, 200.0];
        let width = [6.0f32, 40.0];
        let height = [9.0f32, 80.0];
        for anchor in 0..predictions {
            raw[anchor] = cx[anchor];
            raw[predictions + anchor] = cy[anchor];
            raw[2 * predictions + anchor] = width[anchor];
            raw[3 * predictions + anchor] = height[anchor];
            raw[4 * predictions + anchor] = 0.9;
        }

        let result = decode_attribute_major(&raw, identity(640), 640, 480, 0.35, 0.5).unwrap();
        assert_eq!(result.boxes.len(), 1);
        assert!((result.boxes[0].x - 280.0).abs() < 1e-4);
    }

    #[test]
    fn rejection_reason_names_the_failing_bound() {
        let filter = BoxFilter::default();
        let box_of = |width: f32, height: f32| BoxRect {
            x: 0.0,
            y: 0.0,
            width,
            height,
            confidence: 0.9,
        };
        assert_eq!(filter.rejection_reason(&box_of(6.0, 9.0)), "too_small");
        assert_eq!(
            filter.rejection_reason(&box_of(200.0, 20.0)),
            "extreme_aspect"
        );
        assert_eq!(filter.rejection_reason(&box_of(0.0, 40.0)), "degenerate");
        assert_eq!(
            filter.rejection_reason(&box_of(f32::NAN, 40.0)),
            "degenerate"
        );
    }

    #[test]
    fn strided_decode_rejects_a_truncated_tensor() {
        let error =
            decode_attribute_major(&[0.0; 10], identity(640), 640, 480, 0.3, 0.5).unwrap_err();
        assert!(matches!(error, AiError::Output { .. }));
    }
}
