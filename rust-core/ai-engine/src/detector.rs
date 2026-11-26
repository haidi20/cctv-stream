//! ONNX Runtime detector for YOLOv8/v11 person detection.
//!
//! A detector owns one `ort::Session`. It is intentionally not `Clone`: one
//! session per pipeline keeps memory predictable, and the FFI layer serialises
//! access. Model files are supplied by the caller; this crate never downloads
//! or embeds weights.
//!
//! The session is built for a phone that is also running other apps:
//! intra-op threads are capped, inter-op parallelism stays sequential, and both
//! spinning waits are disabled so the worker parks instead of burning CPU while
//! another app is in the foreground.

use std::path::Path;

use ort::session::builder::GraphOptimizationLevel;
use ort::session::Session;
use ort::value::{TensorRef, ValueType};

use crate::error::AiError;
use crate::nms::BoxRect;
use crate::postprocess::{decode, decode_attribute_major, VALUES_PER_PREDICTION};
use crate::preprocess::{PreprocessScratch, RgbFrame};

/// Which axis of a `[1, 84, N]` or `[1, N, 84]` output holds the predictions.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum OutputLayout {
    AttributeMajor,
    PredictionMajor,
}

/// Validate the tensor layout before reading any prediction.
///
/// A wrong stride used to fall through to the prediction-major decoder and
/// yield plausible-looking garbage, so an unexpected layout is now an error.
fn output_layout(shape: &[i64]) -> Result<OutputLayout, AiError> {
    let attributes = i64::try_from(VALUES_PER_PREDICTION).map_err(|_| AiError::Output {
        reason: "values per prediction do not fit i64".to_string(),
    })?;

    match shape {
        [1, attributes_found, predictions]
            if *attributes_found == attributes && *predictions > 0 =>
        {
            Ok(OutputLayout::AttributeMajor)
        }
        [1, predictions, attributes_found]
            if *attributes_found == attributes && *predictions > 0 =>
        {
            Ok(OutputLayout::PredictionMajor)
        }
        _ => Err(AiError::Output {
            reason: format!("expected [1, {attributes}, N] or [1, N, {attributes}], got {shape:?}"),
        }),
    }
}

/// Read a square, static, 32-aligned input side length out of a model shape.
///
/// Returns `None` for dynamic (`-1`) or non-square inputs, so the caller keeps
/// its configured size instead of failing to load the model.
fn input_size_from_shape(shape: &[i64]) -> Option<u32> {
    match *shape {
        [1, 3, height, width] if height == width && (32..=1280).contains(&height) => {
            let size = u32::try_from(height).ok()?;
            size.is_multiple_of(32).then_some(size)
        }
        _ => None,
    }
}

/// Default model input side length for YOLOv11n exports.
pub const DEFAULT_INPUT_SIZE: u32 = 640;
/// Default confidence floor for a person box.
pub const DEFAULT_CONFIDENCE: f32 = 0.35;
/// Default IoU threshold for NMS.
pub const DEFAULT_IOU: f32 = 0.5;
/// Upper bound for intra-op threads on a phone.
const MAX_INTRA_THREADS: usize = 4;

/// Threads a phone should give one detector session.
///
/// Never returns `0`: ONNX Runtime then picks one thread per core, which
/// oversubscribes a low-end phone and starves whatever app the user opens.
pub fn default_intra_threads() -> usize {
    std::thread::available_parallelism()
        .map_or(2, |threads| threads.get().min(MAX_INTRA_THREADS))
        .max(1)
}

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
            intra_threads: default_intra_threads(),
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

/// Apply the phone-friendly session options.
///
/// Kept out of [`Detector::from_file`] so the fallible builder calls share one
/// mapping to [`AiError`] instead of tripping `clippy::result_large_err` at
/// every chained `and_then`.
fn configure_session(
    builder: ort::session::builder::SessionBuilder,
    intra_threads: usize,
) -> Result<ort::session::builder::SessionBuilder, AiError> {
    // Each ORT builder call consumes the builder and returns a large error, so
    // map it to `AiError` immediately instead of chaining `and_then` (which
    // trips `clippy::result_large_err` six times).
    macro_rules! step {
        ($call:expr) => {
            $call.map_err(|error| AiError::Model {
                reason: error.to_string(),
            })?
        };
    }

    let builder = step!(builder.with_optimization_level(GraphOptimizationLevel::All));
    let builder = if intra_threads == 0 {
        builder
    } else {
        step!(builder.with_intra_threads(intra_threads))
    };
    let builder = step!(builder.with_inter_threads(1));
    let builder = step!(builder.with_parallel_execution(false));
    let builder = step!(builder.with_intra_op_spinning(false));
    let builder = step!(builder.with_inter_op_spinning(false));
    // A rejected custom thread manager is not fatal: inference must keep
    // running on a runtime that refuses it, only at the inherited priority.
    let builder = match builder.with_thread_manager(crate::thread_manager::BackgroundThreadManager)
    {
        Ok(builder) => builder,
        Err(error) => error.recover(),
    };
    Ok(builder)
}

/// Loaded YOLO person detector.
pub struct Detector {
    session: Session,
    config: DetectorConfig,
    scratch: PreprocessScratch,
}

impl Detector {
    /// Load an ONNX model from disk.
    ///
    /// When the model declares a static, square `[1, 3, H, W]` input, `H`
    /// replaces [`DetectorConfig::input_size`]; otherwise the configured size
    /// is kept. This keeps a 416 export from being silently fed 640x640
    /// tensors.
    pub fn from_file(path: impl AsRef<Path>, config: DetectorConfig) -> Result<Self, AiError> {
        let mut config = config.validate()?;
        let environment = crate::runtime::environment()?;
        let mut builder = Session::builder(&environment).map_err(|error| AiError::Model {
            reason: error.to_string(),
        })?;

        // `intra_threads == 0` keeps its documented meaning: ONNX Runtime
        // decides. Only the default config and the Android caller pin a cap.
        builder = configure_session(builder, config.intra_threads)?;

        let session = builder
            .commit_from_file(path.as_ref())
            .map_err(|error| AiError::Model {
                reason: error.to_string(),
            })?;

        if let Some(declared) = session
            .inputs()
            .first()
            .and_then(|input| match input.dtype() {
                ValueType::Tensor { shape, .. } => input_size_from_shape(shape),
                _ => None,
            })
        {
            config.input_size = declared;
        }

        Ok(Self {
            session,
            config,
            scratch: PreprocessScratch::new(),
        })
    }

    /// Effective configuration, after any model-driven input size override.
    pub fn config(&self) -> DetectorConfig {
        self.config
    }

    /// Detect people in one RGB frame.
    pub fn detect(&mut self, frame: RgbFrame<'_>) -> Result<Vec<BoxRect>, AiError> {
        let letterbox = self
            .scratch
            .preprocess_into(frame, self.config.input_size)?;
        let shape = [
            1_i64,
            3,
            i64::from(self.config.input_size),
            i64::from(self.config.input_size),
        ];
        let input =
            TensorRef::from_array_view((shape, self.scratch.tensor())).map_err(|error| {
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

        let layout = output_layout(shape)?;
        let expected = shape.iter().try_fold(1_usize, |total, dimension| {
            let dimension = usize::try_from(*dimension).map_err(|_| AiError::Output {
                reason: format!("negative or oversized dimension in {shape:?}"),
            })?;
            total.checked_mul(dimension).ok_or_else(|| AiError::Output {
                reason: "tensor size overflow".to_string(),
            })
        })?;
        let predictions = data.len() / VALUES_PER_PREDICTION;
        if predictions == 0
            || expected != data.len()
            || !data.len().is_multiple_of(VALUES_PER_PREDICTION)
        {
            return Err(AiError::Output {
                reason: format!(
                    "shape {shape:?} has {} values, not a multiple of {VALUES_PER_PREDICTION}",
                    data.len()
                ),
            });
        }

        // Decode straight from the ONNX buffer. Transposing into a second
        // `Vec<f32>` would allocate and copy ~2.8 MB on every 640x640 frame;
        // `decode_attribute_major` reads with a stride instead.
        let detections = match layout {
            OutputLayout::AttributeMajor => decode_attribute_major(
                data,
                letterbox,
                frame.width,
                frame.height,
                self.config.confidence_threshold,
                self.config.iou_threshold,
            )?,
            OutputLayout::PredictionMajor => decode(
                data,
                letterbox,
                frame.width,
                frame.height,
                self.config.confidence_threshold,
                self.config.iou_threshold,
            )?,
        };

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

    #[test]
    fn zero_threads_still_means_let_onnx_runtime_decide() {
        // Documented contract on `DetectorConfig::intra_threads`; the Android
        // cap must not silently turn "auto" into a single thread.
        let config = DetectorConfig {
            intra_threads: 0,
            ..DetectorConfig::default()
        };
        assert_eq!(config.intra_threads, 0);
        assert!(config.validate().is_ok());
    }

    #[test]
    fn default_threads_stay_within_the_phone_budget() {
        let threads = default_intra_threads();
        assert!((1..=MAX_INTRA_THREADS).contains(&threads));
        assert_eq!(DetectorConfig::default().intra_threads, threads);
    }

    #[test]
    fn attribute_major_layout_is_recognised() {
        assert_eq!(
            output_layout(&[1, 84, 8400]).unwrap(),
            OutputLayout::AttributeMajor
        );
    }

    #[test]
    fn prediction_major_layout_is_recognised() {
        assert_eq!(
            output_layout(&[1, 8400, 84]).unwrap(),
            OutputLayout::PredictionMajor
        );
    }

    #[test]
    fn wrong_attribute_count_is_rejected_instead_of_guessed() {
        assert!(matches!(
            output_layout(&[1, 85, 8400]),
            Err(AiError::Output { .. })
        ));
        assert!(matches!(
            output_layout(&[1, 8400]),
            Err(AiError::Output { .. })
        ));
        assert!(matches!(
            output_layout(&[1, 84, 0]),
            Err(AiError::Output { .. })
        ));
    }

    #[test]
    fn static_square_model_input_size_is_adopted() {
        assert_eq!(input_size_from_shape(&[1, 3, 416, 416]), Some(416));
        assert_eq!(input_size_from_shape(&[1, 3, 320, 320]), Some(320));
    }

    #[test]
    fn dynamic_non_square_or_misaligned_inputs_are_ignored() {
        assert_eq!(input_size_from_shape(&[1, 3, -1, -1]), None);
        assert_eq!(input_size_from_shape(&[1, 3, 650, 650]), None);
        assert_eq!(input_size_from_shape(&[1, 3, 640, 416]), None);
        assert_eq!(input_size_from_shape(&[2, 3, 640, 640]), None);
        assert_eq!(input_size_from_shape(&[1, 3, 16, 16]), None);
        assert_eq!(input_size_from_shape(&[1, 3, 1408, 1408]), None);
        assert_eq!(input_size_from_shape(&[1, 84, 8400]), None);
    }
}
