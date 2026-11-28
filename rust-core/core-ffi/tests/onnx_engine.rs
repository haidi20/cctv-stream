//! End-to-end FFI test with real ONNX Runtime inference.
//!
//! Set `CCTV_ONNX_TEST_MODEL_640` to a 640x640 model produced by
//! `tools/onnx-test/gen_synthetic_model.py`. Without the variable the test is
//! skipped.

use core_ffi::{Engine, EngineConfig, TableStatus};
use std::path::PathBuf;
use std::sync::Arc;

const TABLES: &str = r#"{
        "version": 2,
        "video": { "width": 640, "height": 640, "aspect_ratio": "1:1" },
        "tables": [
            { "id": "meja-01", "polygon": [
                { "x": 0.3, "y": 0.3 },
                { "x": 0.7, "y": 0.3 },
                { "x": 0.7, "y": 0.7 },
                { "x": 0.3, "y": 0.7 }
            ] }
        ]
    }"#;

/// Start an engine over the synthetic model at `model_path`.
///
/// The synthetic model plants one person in the middle of the frame, so the
/// only box the engine can return is the one the polygon surrounds. Returns the
/// marker file the caller must delete: host builds link ONNX Runtime
/// statically, and the path is only checked for existence.
fn start_engine(model_path: &str, tag: &str) -> (Arc<Engine>, PathBuf) {
    start_engine_with(model_path, tag, |_| {})
}

/// Same as [`start_engine`], with a chance to change the session settings.
fn start_engine_with(
    model_path: &str,
    tag: &str,
    tune: impl FnOnce(&mut EngineConfig),
) -> (Arc<Engine>, PathBuf) {
    let runtime_marker = std::env::temp_dir().join(format!(
        "cctv-onnx-runtime-{}-{tag}.marker",
        std::process::id()
    ));
    std::fs::write(&runtime_marker, b"host builds link ONNX Runtime statically")
        .expect("create runtime marker");
    let engine = Engine::new();
    let mut config = EngineConfig {
        camera_id: "meja-test".to_string(),
        model_path: model_path.to_string(),
        onnx_runtime_path: runtime_marker.to_string_lossy().into_owned(),
        tables_json: TABLES.to_string(),
        inference_fps: 5.0,
        debounce_ms: 0,
        intra_threads: 0,
        confidence_threshold: None,
        iou_threshold: None,
        min_box_px: None,
    };
    tune(&mut config);
    engine.start(config).expect("start engine");
    (engine, runtime_marker)
}

#[test]
fn engine_turns_a_planted_person_into_an_occupied_event() {
    let Ok(model_path) = std::env::var("CCTV_ONNX_TEST_MODEL_640") else {
        eprintln!("skipped: CCTV_ONNX_TEST_MODEL_640 is not set");
        return;
    };
    let (engine, runtime_marker) = start_engine(&model_path, "event");

    let frame = vec![128_u8; 640 * 640 * 3];
    let events = engine.push_frame(640, 640, frame).expect("analyse frame");

    assert_eq!(events.len(), 1);
    assert_eq!(events[0].table_id, "meja-01");
    assert_eq!(events[0].status, TableStatus::Occupied);
    assert_eq!(events[0].persons_in_region, 1);
    assert!((events[0].confidence - 0.9).abs() < 1e-5);

    let health = engine.health().expect("health");
    assert_eq!(health.camera_id, "meja-test");
    assert!(health.model_ready);
    assert_eq!(health.tables_occupied, 1);
    assert_eq!(engine.stop(), Ok(()));
    let _ = std::fs::remove_file(runtime_marker);
}

/// Q-12: the payload carries the frame it was measured on, not the read time.
#[test]
fn analysed_boxes_carry_the_frame_they_came_from() {
    let Ok(model_path) = std::env::var("CCTV_ONNX_TEST_MODEL_640") else {
        eprintln!("skipped: CCTV_ONNX_TEST_MODEL_640 is not set");
        return;
    };
    let (engine, runtime_marker) = start_engine(&model_path, "stamp");

    let frame = vec![128_u8; 640 * 640 * 3];
    engine
        .push_frame(640, 640, frame.clone())
        .expect("analyse frame");

    let boxes = engine.last_detections().expect("detections");
    let health = engine.health().expect("health");
    assert_eq!(boxes.len(), 1, "the synthetic model plants one person");
    assert_eq!(
        boxes[0].frame_seq, health.frames_sampled,
        "the newest box is stamped with the analysed frame, not a later one"
    );
    assert_eq!(boxes[0].frame_seq, 1, "the first analysed frame is 1");
    assert!(
        boxes[0].captured_at_ms <= health.uptime_ms,
        "a box cannot have been captured after the snapshot that reports it"
    );
    let captured = boxes[0].captured_at_ms;

    // A second frame inside the same 5 FPS slot is dropped by the sampler, so
    // the boxes must keep the stamp of the frame that was actually analysed.
    engine
        .push_frame(640, 640, frame)
        .expect("a throttled frame is not an error");
    let again = engine.last_detections().expect("detections");
    let health_after = engine.health().expect("health");
    assert_eq!(health_after.frames_sampled, 1);
    assert_eq!(health_after.frames_dropped, 1);
    assert_eq!(again[0].frame_seq, 1);
    assert_eq!(
        again[0].captured_at_ms, captured,
        "a reader must never re-stamp the boxes it serves"
    );

    assert_eq!(engine.stop(), Ok(()));
    let _ = std::fs::remove_file(runtime_marker);
}

/// Q-05: the plausibility floor the caller sends is the floor the decoder uses.
///
/// Until the filter reached `DetectorConfig`, changing `min_box_px` had no
/// effect at all: `DecodeLayout::for_attributes` kept its own default.
#[test]
fn box_filter_bounds_reach_the_decoder() {
    let Ok(model_path) = std::env::var("CCTV_ONNX_TEST_MODEL_640") else {
        eprintln!("skipped: CCTV_ONNX_TEST_MODEL_640 is not set");
        return;
    };
    let frame = vec![128_u8; 640 * 640 * 3];

    // The fixture box is 160x320 px. A 512 px floor is legal (the FFI bound)
    // and must remove it; if the filter never reached the decoder the box would
    // still be there.
    let (strict, strict_marker) = start_engine_with(&model_path, "min-box", |config| {
        config.min_box_px = Some(512);
    });
    strict
        .push_frame(640, 640, frame.clone())
        .expect("analyse frame");
    assert!(
        strict.last_detections().expect("detections").is_empty(),
        "a floor taller than the planted box must remove it"
    );
    assert_eq!(strict.stop(), Ok(()));
    let _ = std::fs::remove_file(strict_marker);

    let (normal, normal_marker) = start_engine(&model_path, "min-box-default");
    normal.push_frame(640, 640, frame).expect("analyse frame");
    assert_eq!(
        normal.last_detections().expect("detections").len(),
        1,
        "the default floor keeps the same box, so the drop above was the filter"
    );
    assert_eq!(normal.stop(), Ok(()));
    let _ = std::fs::remove_file(normal_marker);
}
