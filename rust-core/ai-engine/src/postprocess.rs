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
pub fn decode(
    raw: &[f32],
    letterbox: Letterbox,
    source_width: u32,
    source_height: u32,
    confidence_threshold: f32,
    iou_threshold: f32,
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

    Ok(Detections {
        boxes: suppress(boxes, iou_threshold),
    })
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

    Ok(Detections {
        boxes: suppress(boxes, iou_threshold),
    })
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
    fn strided_decode_rejects_a_truncated_tensor() {
        let error =
            decode_attribute_major(&[0.0; 10], identity(640), 640, 480, 0.3, 0.5).unwrap_err();
        assert!(matches!(error, AiError::Output { .. }));
    }
}
