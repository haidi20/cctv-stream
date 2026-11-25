//! Edge person detection for table occupancy.
//!
//! The crate is split so the expensive, hardware-dependent part (`ort`) is a
//! thin shell around pure functions:
//!
//! - [`preprocess()`] letterboxes an RGB frame into an `NCHW` tensor.
//! - [`postprocess`] decodes YOLO output and [`nms`] removes duplicates.
//! - [`detector`] owns the ONNX session and glues them together.
//!
//! Tests for the pure parts run on any host without a model file. Only tests
//! that need real weights are gated behind an environment variable.

pub mod detector;
pub mod error;
pub mod nms;
pub mod postprocess;
pub mod preprocess;
pub mod runtime;

pub use detector::{Detector, DetectorConfig, DEFAULT_CONFIDENCE, DEFAULT_INPUT_SIZE, DEFAULT_IOU};
pub use error::AiError;
pub use nms::{iou, suppress, BoxRect};
pub use postprocess::{
    decode, decode_attribute_major, transpose_attribute_major, Detections, VALUES_PER_PREDICTION,
};
pub use preprocess::{preprocess, Letterbox, Preprocessed, RgbFrame};
pub use runtime::configure_library_path;
