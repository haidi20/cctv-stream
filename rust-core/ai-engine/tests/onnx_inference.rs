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
