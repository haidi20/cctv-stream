//! ONNX Runtime detector for YOLOv8/v11 person detection.
//!
//! A detector owns one `ort::Session`. It is intentionally not `Clone`: one
//! session per pipeline keeps memory predictable, and the FFI layer serialises
//! access. Model files are supplied by the caller; this crate never downloads
//! or embeds weights.

use std::path::Path;

use ort::session::Session;
use ort::value::TensorRef;

use crate::error::AiError;
use crate::nms::BoxRect;
use crate::postprocess::{decode, transpose_attribute_major, VALUES_PER_PREDICTION};
use crate::preprocess::{preprocess, RgbFrame};

/// Default model input side length for YOLOv11n exports.
pub const DEFAULT_INPUT_SIZE: u32 = 640;
/// Default confidence floor for a person box.
pub const DEFAULT_CONFIDENCE: f32 = 0.35;
/// Default IoU threshold for NMS.
pub const DEFAULT_IOU: f32 = 0.5;

/// Detector settings that live outside the model file.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DetectorConfig {
    /// Square model input size.
    pub input_size: u32,
    /// Minimum person confidence.
    pub confidence_threshold: f32,
    /// NMS IoU threshold.
    pub iou_threshold: f32,
    /// Session threads; `0` lets ONNX Runtime decide.
    pub intra_threads: usize,
}

impl Default for DetectorConfig {
    fn default() -> Self {
        Self {
            input_size: DEFAULT_INPUT_SIZE,
            confidence_threshold: DEFAULT_CONFIDENCE,
            iou_threshold: DEFAULT_IOU,
            intra_threads: 0,
        }
    }
}

impl DetectorConfig {
    /// Reject values that would produce nonsense tensors.
    pub fn validate(self) -> Result<Self, AiError> {
        if self.input_size < 32 || self.input_size > 1280 {
            return Err(AiError::Config {
                reason: "input_size must be between 32 and 1280".to_string(),
            });
        }
        if !self.input_size.is_multiple_of(32) {
            return Err(AiError::Config {
                reason: "input_size must be a multiple of 32".to_string(),
            });
        }
        if !(0.0..=1.0).contains(&self.confidence_threshold) {
            return Err(AiError::Config {
                reason: "confidence_threshold must be in 0..=1".to_string(),
            });
        }
        if !(0.0..=1.0).contains(&self.iou_threshold) {
            return Err(AiError::Config {
                reason: "iou_threshold must be in 0..=1".to_string(),
            });
        }
        Ok(self)
    }
}

/// Loaded YOLO person detector.
pub struct Detector {
    session: Session,
    config: DetectorConfig,
}

impl Detector {
    /// Load an ONNX model from disk.
    pub fn from_file(path: impl AsRef<Path>, config: DetectorConfig) -> Result<Self, AiError> {
        let config = config.validate()?;
        let environment = crate::runtime::environment()?;
        let mut builder = Session::builder(&environment).map_err(|error| AiError::Model {
            reason: error.to_string(),
        })?;

        if config.intra_threads > 0 {
            builder = builder
                .with_intra_threads(config.intra_threads)
                .map_err(|error| AiError::Model {
                    reason: error.to_string(),
                })?;
        }

        let session = builder
            .commit_from_file(path.as_ref())
            .map_err(|error| AiError::Model {
                reason: error.to_string(),
            })?;

        Ok(Self { session, config })
    }

    /// Effective configuration.
    pub fn config(&self) -> DetectorConfig {
        self.config
    }

    /// Detect people in one RGB frame.
    pub fn detect(&mut self, frame: RgbFrame<'_>) -> Result<Vec<BoxRect>, AiError> {
        let prepared = preprocess(frame, self.config.input_size)?;
        let shape = [
            1_i64,
            3,
            i64::from(self.config.input_size),
            i64::from(self.config.input_size),
        ];
        let input =
            TensorRef::from_array_view((shape, prepared.tensor.as_slice())).map_err(|error| {
                AiError::Inference {
                    reason: error.to_string(),
                }
            })?;

        let outputs =
            self.session
                .run(ort::inputs![input])
                .map_err(|error| AiError::Inference {
                    reason: error.to_string(),
                })?;

        let (_, value) = outputs.iter().next().ok_or_else(|| AiError::Output {
            reason: "model returned no outputs".to_string(),
        })?;

        let (shape, data) = value
            .try_extract_tensor::<f32>()
            .map_err(|error| AiError::Output {
                reason: error.to_string(),
            })?;

        let values_per_row = VALUES_PER_PREDICTION;
        let expected: usize = shape
            .iter()
            .filter_map(|dim| usize::try_from(*dim).ok())
            .product();
        if expected != data.len() {
            return Err(AiError::Output {
                reason: format!("shape {shape:?} does not match {} values", data.len()),
            });
        }

        let prediction_major = if data.len() % values_per_row != 0 {
            return Err(AiError::Output {
                reason: format!(
                    "{} values is not a multiple of {values_per_row}",
                    data.len()
                ),
            });
        } else if shape.len() == 3 && shape[1] == values_per_row as i64 {
            transpose_attribute_major(data)?
        } else {
            data.to_vec()
        };

        let detections = decode(
            &prediction_major,
            prepared.letterbox,
            frame.width,
            frame.height,
            self.config.confidence_threshold,
            self.config.iou_threshold,
        )?;

        Ok(detections.boxes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_input_size_that_is_not_a_multiple_of_32() {
        let config = DetectorConfig {
            input_size: 650,
            ..DetectorConfig::default()
        };
        assert!(config.validate().is_err());
    }

    #[test]
    fn rejects_confidence_outside_unit_range() {
        let config = DetectorConfig {
            confidence_threshold: 1.5,
            ..DetectorConfig::default()
        };
        assert!(config.validate().is_err());
    }

    #[test]
    fn defaults_are_valid() {
        assert!(DetectorConfig::default().validate().is_ok());
    }
}
