//! Real ONNX Runtime inference against a generated fixture model.
//!
//! Set `CCTV_ONNX_TEST_MODEL` to a 32x32 model produced by
//! `tools/onnx-test/gen_synthetic_model.py`. Without the variable the test is
//! skipped so normal `cargo test` stays hermetic.

use ai_engine::{Detector, DetectorConfig, RgbFrame};

#[test]
fn synthetic_model_returns_the_planted_person_box() {
    let Ok(model_path) = std::env::var("CCTV_ONNX_TEST_MODEL") else {
        eprintln!("skipped: CCTV_ONNX_TEST_MODEL is not set");
        return;
    };

    let config = DetectorConfig {
        input_size: 32,
        confidence_threshold: 0.35,
        iou_threshold: 0.5,
        intra_threads: 1,
    };
    let mut detector = Detector::from_file(&model_path, config).expect("load fixture model");
    let pixels = vec![128_u8; 64 * 64 * 3];
    let frame = RgbFrame::new(64, 64, &pixels).expect("valid frame");

    let boxes = detector.detect(frame).expect("run inference");

    assert_eq!(boxes.len(), 1);
    assert!((boxes[0].confidence - 0.9).abs() < 1e-5);
    let centre_x = boxes[0].x + boxes[0].width / 2.0;
    let centre_y = boxes[0].y + boxes[0].height / 2.0;
    assert!((centre_x - 32.0).abs() < 0.5, "centre x was {centre_x}");
    assert!((centre_y - 32.0).abs() < 0.5, "centre y was {centre_y}");
}

/// A 416 request against a 640 export must adopt the model's shape.
///
/// Without the override, preprocessing produced a 416x416 tensor for a model
/// that only accepts 640x640, so the session failed at run time. This exercises
/// the override against real ONNX Runtime, not just the pure shape helper.
#[test]
fn a_smaller_configured_size_is_overridden_by_the_model_export() {
    let Ok(model_path) = std::env::var("CCTV_ONNX_TEST_MODEL_640") else {
        eprintln!("skipped: CCTV_ONNX_TEST_MODEL_640 is not set");
        return;
    };

    let config = DetectorConfig {
        input_size: 416,
        confidence_threshold: 0.35,
        iou_threshold: 0.5,
        intra_threads: 1,
    };
    let mut detector = Detector::from_file(&model_path, config).expect("load 640 fixture");
    assert_eq!(
        detector.config().input_size,
        640,
        "the model declaration must win over the requested 416"
    );

    let pixels = vec![128_u8; 320 * 240 * 3];
    let frame = RgbFrame::new(320, 240, &pixels).expect("valid frame");
    let boxes = detector
        .detect(frame)
        .expect("run inference at the model size");
    assert_eq!(boxes.len(), 1);
    let centre_x = boxes[0].x + boxes[0].width / 2.0;
    let centre_y = boxes[0].y + boxes[0].height / 2.0;
    assert!((centre_x - 160.0).abs() < 1.0, "centre x was {centre_x}");
    assert!((centre_y - 120.0).abs() < 1.0, "centre y was {centre_y}");
}

/// A 320 export is the candidate for low-RAM phones. The fixture only proves
/// the shape path; accuracy still needs a real model and a field test.
#[test]
fn a_320_export_runs_the_whole_preprocess_and_decode_path() {
    let Ok(model_path) = std::env::var("CCTV_ONNX_TEST_MODEL_320") else {
        eprintln!("skipped: CCTV_ONNX_TEST_MODEL_320 is not set");
        return;
    };

    let config = DetectorConfig {
        input_size: 320,
        confidence_threshold: 0.35,
        iou_threshold: 0.5,
        intra_threads: 1,
    };
    let mut detector = Detector::from_file(&model_path, config).expect("load 320 fixture");
    assert_eq!(detector.config().input_size, 320);

    let pixels = vec![128_u8; 640 * 360 * 3];
    let frame = RgbFrame::new(640, 360, &pixels).expect("valid frame");
    let boxes = detector.detect(frame).expect("run inference at 320");

    assert_eq!(boxes.len(), 1);
    let centre_x = boxes[0].x + boxes[0].width / 2.0;
    let centre_y = boxes[0].y + boxes[0].height / 2.0;
    assert!((centre_x - 320.0).abs() < 1.0, "centre x was {centre_x}");
    assert!((centre_y - 180.0).abs() < 1.0, "centre y was {centre_y}");
}
