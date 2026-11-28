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
pub mod quality;
pub mod runtime;
pub mod thread_manager;

pub use detector::{Detector, DetectorConfig, DEFAULT_CONFIDENCE, DEFAULT_INPUT_SIZE, DEFAULT_IOU};
pub use error::AiError;
pub use nms::{iou, suppress, BoxRect};
pub use postprocess::{
    decode, decode_attribute_major, decode_attribute_major_with_filter,
    decode_attribute_major_with_layout, decode_with_filter, decode_with_layout,
    transpose_attribute_major, transpose_attribute_major_with, BoxEncoding, BoxFilter,
    DecodeLayout, Detections, BOX_ATTRIBUTES, DEFAULT_MIN_SIDE_PX, MAX_ASPECT, MAX_ATTRIBUTES,
    MIN_ASPECT, VALUES_PER_PREDICTION,
};
pub use preprocess::{preprocess, Letterbox, Preprocessed, RgbFrame};
pub use quality::{
    height_histogram, match_frame, normalized_iou, summarize, worst_frames, ConfusionCounts,
    FrameMatch, FrameScore, HeightBucket, MatchedPair, NormalizedBox, DEFAULT_MATCH_IOU,
    HEIGHT_BUCKET_EDGES_PX,
};
pub use runtime::configure_library_path;
