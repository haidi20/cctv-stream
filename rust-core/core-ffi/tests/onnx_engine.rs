//! End-to-end FFI test with real ONNX Runtime inference.
//!
//! Set `CCTV_ONNX_TEST_MODEL_640` to a 640x640 model produced by
//! `tools/onnx-test/gen_synthetic_model.py`. Without the variable the test is
//! skipped.

use core_ffi::{Engine, EngineConfig, TableStatus};

#[test]
fn engine_turns_a_planted_person_into_an_occupied_event() {
    let Ok(model_path) = std::env::var("CCTV_ONNX_TEST_MODEL_640") else {
        eprintln!("skipped: CCTV_ONNX_TEST_MODEL_640 is not set");
        return;
    };

    let tables = r#"{
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
    let runtime_marker =
        std::env::temp_dir().join(format!("cctv-onnx-runtime-{}.marker", std::process::id()));
    std::fs::write(&runtime_marker, b"host builds link ONNX Runtime statically")
        .expect("create runtime marker");
    let engine = Engine::new();
    engine
        .start(EngineConfig {
            camera_id: "meja-test".to_string(),
            model_path: model_path.clone(),
            // Host builds link ONNX Runtime statically; the path is only
            // checked for existence before the session is created.
            onnx_runtime_path: runtime_marker.to_string_lossy().into_owned(),
            tables_json: tables.to_string(),
            inference_fps: 5.0,
            debounce_ms: 0,
            intra_threads: 0,
        })
        .expect("start engine");

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
