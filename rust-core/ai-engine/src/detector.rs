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
use crate::postprocess::{
    decode_attribute_major_with_layout, decode_with_layout, DecodeLayout, BOX_ATTRIBUTES,
    MAX_ATTRIBUTES,
};
use crate::preprocess::{PreprocessScratch, RgbFrame};

/// Which axis of a `[1, A, N]` or `[1, N, A]` output holds the predictions.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum OutputLayout {
    AttributeMajor,
    PredictionMajor,
}

impl OutputLayout {
    fn as_str(self) -> &'static str {
        match self {
            Self::AttributeMajor => "attribute-major",
            Self::PredictionMajor => "prediction-major",
        }
    }
}

/// Attributes of an export that already ran NMS: `x1, y1, x2, y2, score, class`.
const NMS_ATTRIBUTES: usize = BOX_ATTRIBUTES + 2;

/// One model output, classified from its declared or observed shape.
#[derive(Debug, Clone, Copy, PartialEq)]
struct OutputSpec {
    layout: OutputLayout,
    /// Values per prediction, including the four box values.
    attributes: usize,
    predictions: usize,
    /// How to read a prediction row, handed to the post-processor unchanged.
    decode: DecodeLayout,
}

impl OutputSpec {
    /// Total number of values the tensor must hold.
    fn values(self) -> Result<usize, AiError> {
        self.attributes
            .checked_mul(self.predictions)
            .ok_or_else(|| AiError::Output {
                reason: "tensor size overflow".to_string(),
            })
    }
}

/// Classify a model output shape.
///
/// The attribute axis is the one whose size is plausible for a prediction row
/// (`5..=MAX_ATTRIBUTES`), so `[1, 8400, 84]` is not mistaken for 8400
/// attributes. `[1, N, 6]` is special-cased first: that is the shape of an
/// export that already ran NMS and therefore emits corner boxes plus a class.
///
/// A shape that fits neither reading is an error rather than a guess: the old
/// behaviour of falling back to the 84-attribute stride produced plausible
/// garbage boxes from a perfectly good tensor.
fn output_spec(shape: &[i64]) -> Result<OutputSpec, AiError> {
    let [1, first, second] = *shape else {
        return Err(unsupported_shape(shape));
    };
    let first = positive_dimension(first).ok_or_else(|| unsupported_shape(shape))?;
    let second = positive_dimension(second).ok_or_else(|| unsupported_shape(shape))?;

    let (layout, attributes, predictions) = if second == NMS_ATTRIBUTES && first > NMS_ATTRIBUTES {
        (OutputLayout::PredictionMajor, NMS_ATTRIBUTES, first)
    } else {
        let first_is_attributes = is_attribute_count(first);
        let second_is_attributes = is_attribute_count(second);
        match (first_is_attributes, second_is_attributes) {
            (true, false) => (OutputLayout::AttributeMajor, first, second),
            (false, true) => (OutputLayout::PredictionMajor, second, first),
            // Both axes are plausible: the canonical Ultralytics export puts
            // the attributes first, so `[1, A, N]` wins.
            (true, true) => (OutputLayout::AttributeMajor, first, second),
            (false, false) => return Err(unsupported_shape(shape)),
        }
    };

    let decode = if layout == OutputLayout::PredictionMajor && attributes == NMS_ATTRIBUTES {
        DecodeLayout::nms_corners()
    } else {
        DecodeLayout::for_attributes(attributes)
    };

    Ok(OutputSpec {
        layout,
        attributes,
        predictions,
        decode,
    })
}

fn positive_dimension(value: i64) -> Option<usize> {
    usize::try_from(value)
        .ok()
        .filter(|dimension| *dimension > 0)
}

fn is_attribute_count(value: usize) -> bool {
    (BOX_ATTRIBUTES + 1..=MAX_ATTRIBUTES).contains(&value)
}

/// Error that names the shape found and the shapes accepted.
fn unsupported_shape(shape: &[i64]) -> AiError {
    AiError::Output {
        reason: format!(
            "unsupported output shape {shape:?}: expected [1, 4+classes, N] or \
             [1, N, 4+classes] with 5..={MAX_ATTRIBUTES} attributes, or \
             [1, N, {NMS_ATTRIBUTES}] for an export that already ran NMS"
        ),
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

/// Output shape the model declares, when it is a static tensor.
///
/// Dynamic axes (`-1`) are returned as they are; the caller only resolves a
/// layout when every dimension is positive.
fn declared_output_shape(session: &Session) -> Option<Vec<i64>> {
    session
        .outputs()
        .first()
        .and_then(|output| match output.dtype() {
            ValueType::Tensor { shape, .. } => Some(shape.to_vec()),
            _ => None,
        })
}

/// Loaded YOLO person detector.
pub struct Detector {
    session: Session,
    config: DetectorConfig,
    scratch: PreprocessScratch,
    /// Whether the resolved output layout has already been logged.
    layout_logged: bool,
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

        // Log the layout once, at load time, when the model declares a static
        // shape. A model that declares something this crate cannot decode is
        // rejected here instead of failing on the first frame the user sees.
        if let Some(shape) = declared_output_shape(&session) {
            if shape.iter().all(|dimension| *dimension > 0) {
                let spec = output_spec(&shape)?;
                tracing::info!(
                    attributes = spec.attributes,
                    predictions = spec.predictions,
                    layout = spec.layout.as_str(),
                    encoding = ?spec.decode.encoding,
                    "model declares a supported output layout"
                );
            } else {
                tracing::debug!(
                    shape = ?shape,
                    "model output shape is dynamic; layout is resolved on the first frame"
                );
            }
        }

        Ok(Self {
            session,
            config,
            scratch: PreprocessScratch::new(),
            layout_logged: false,
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

        let spec = output_spec(shape)?;
        let expected = spec.values()?;
        if expected != data.len() {
            return Err(AiError::Output {
                reason: format!(
                    "shape {shape:?} promises {expected} values, tensor holds {}",
                    data.len()
                ),
            });
        }

        if !self.layout_logged {
            tracing::info!(
                attributes = spec.attributes,
                predictions = spec.predictions,
                layout = spec.layout.as_str(),
                encoding = ?spec.decode.encoding,
                "detector output layout"
            );
            self.layout_logged = true;
        }

        // Decode straight from the ONNX buffer. Transposing into a second
        // `Vec<f32>` would allocate and copy ~2.8 MB on every 640x640 frame;
        // the attribute-major path reads with a stride instead.
        let detections = match spec.layout {
            OutputLayout::AttributeMajor => decode_attribute_major_with_layout(
                data,
                letterbox,
                frame.width,
                frame.height,
                self.config.confidence_threshold,
                self.config.iou_threshold,
                spec.decode,
            )?,
            OutputLayout::PredictionMajor => decode_with_layout(
                data,
                letterbox,
                frame.width,
                frame.height,
                self.config.confidence_threshold,
                self.config.iou_threshold,
                spec.decode,
            )?,
        };

        Ok(detections.boxes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::postprocess::BoxEncoding;

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

    fn identity_letterbox(size: u32) -> crate::preprocess::Letterbox {
        crate::preprocess::Letterbox {
            scale: 1.0,
            pad_x: 0.0,
            pad_y: 0.0,
            size,
        }
    }

    #[test]
    fn attribute_major_layout_is_recognised() {
        let spec = output_spec(&[1, 84, 8400]).unwrap();
        assert_eq!(spec.layout, OutputLayout::AttributeMajor);
        assert_eq!(spec.attributes, 84);
        assert_eq!(spec.predictions, 8400);
        assert_eq!(spec.decode.encoding, BoxEncoding::Center);
        assert_eq!(spec.decode.class_index, None);
    }

    #[test]
    fn prediction_major_layout_is_recognised() {
        let spec = output_spec(&[1, 8400, 84]).unwrap();
        assert_eq!(spec.layout, OutputLayout::PredictionMajor);
        assert_eq!(spec.attributes, 84);
        assert_eq!(spec.predictions, 8400);
    }

    #[test]
    fn extra_class_rows_are_accepted_now() {
        // 85 attributes is a YOLOv5-style export: 4 box + objectness + 80
        // classes. It used to be rejected outright.
        let spec = output_spec(&[1, 85, 8400]).unwrap();
        assert_eq!(spec.layout, OutputLayout::AttributeMajor);
        assert_eq!(spec.attributes, 85);
        assert_eq!(spec.predictions, 8400);
    }

    #[test]
    fn unsupported_shape_error_names_the_shape() {
        let error = output_spec(&[1, 3, 8400]).unwrap_err();
        let message = error.to_string();
        assert!(message.contains("[1, 3, 8400]"), "message was {message}");
        assert!(message.contains("expected"), "message was {message}");
        assert!(message.contains("4+classes"), "message was {message}");

        // A 2-D output and an empty prediction axis are still rejected, and
        // both name the shape they found.
        for shape in [&[1_i64, 8400][..], &[1, 84, 0][..], &[1, 1, 1][..]] {
            let error = output_spec(shape).unwrap_err();
            assert!(
                error.to_string().contains(&format!("{shape:?}")),
                "shape {shape:?} produced {error}"
            );
        }
    }

    #[test]
    fn person_only_model_with_five_attributes_decodes() {
        // A one-class fine-tune exports [1, 5, N]: four box values plus the
        // person score, with no class rows at all.
        let predictions = 2_usize;
        let mut raw = vec![0.0f32; 5 * predictions];
        raw[0] = 320.0;
        raw[predictions] = 240.0;
        raw[2 * predictions] = 60.0;
        raw[3 * predictions] = 120.0;
        raw[4 * predictions] = 0.9;

        let spec = output_spec(&[1, 5, predictions as i64]).unwrap();
        assert_eq!(spec.layout, OutputLayout::AttributeMajor);
        assert_eq!(spec.attributes, 5);
        assert_eq!(spec.predictions, 2);
        assert_eq!(spec.decode.encoding, BoxEncoding::Center);

        let detections = decode_attribute_major_with_layout(
            &raw,
            identity_letterbox(640),
            640,
            480,
            0.35,
            0.5,
            spec.decode,
        )
        .unwrap();
        assert_eq!(detections.boxes.len(), 1);
        assert!((detections.boxes[0].x - 290.0).abs() < 1e-3);
        assert!((detections.boxes[0].width - 60.0).abs() < 1e-3);
        assert!((detections.boxes[0].height - 120.0).abs() < 1e-3);
    }

    #[test]
    fn torchvision_e2e_output_decodes_without_nms() {
        // [1, 300, 6]: the model already ran NMS and emits corner boxes plus
        // a class id. Class 0 is a person, class 1 is not, and the low-score
        // row must not survive the confidence floor.
        let boxes = 300_usize;
        let mut raw = vec![0.0f32; boxes * NMS_ATTRIBUTES];
        raw[0..6].copy_from_slice(&[100.0, 50.0, 180.0, 250.0, 0.9, 0.0]);
        raw[6..12].copy_from_slice(&[100.0, 50.0, 180.0, 250.0, 0.95, 1.0]);
        raw[12..18].copy_from_slice(&[100.0, 50.0, 180.0, 250.0, 0.1, 0.0]);

        let spec = output_spec(&[1, boxes as i64, 6]).unwrap();
        assert_eq!(spec.layout, OutputLayout::PredictionMajor);
        assert_eq!(spec.attributes, 6);
        assert_eq!(spec.decode.encoding, BoxEncoding::Corners);
        assert_eq!(spec.decode.class_index, Some(5));

        let detections = decode_with_layout(
            &raw,
            identity_letterbox(640),
            640,
            480,
            0.35,
            0.5,
            spec.decode,
        )
        .unwrap();
        assert_eq!(detections.boxes.len(), 1);
        let found = detections.boxes[0];
        assert!((found.x - 100.0).abs() < 1e-3);
        assert!((found.y - 50.0).abs() < 1e-3);
        assert!((found.width - 80.0).abs() < 1e-3);
        assert!((found.height - 200.0).abs() < 1e-3);
        assert!((found.confidence - 0.9).abs() < 1e-3);
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
