//! The contract the Kotlin app relies on, exercised through the public FFI surface.
//!
//! Every Kotlin test fakes `EngineInterface`, and every `core-ffi` unit test
//! touches one method at a time. This suite runs the *sequence* the Kotlin
//! layers actually perform - `RustCameraSession.open` (stopped engine, no model),
//! the mapping screen's `applyTables`, the Home poll (`health`, `tables`,
//! `lastDetections`), and `stop` - against the real engine, using the canonical
//! fixture that `:core:model`'s `ContractSchemaTest` validates on the Kotlin
//! side. A change in either layer fails here before it reaches a device.
//!
//! No ONNX model and no stream are needed: `set_tables`, `tables`, `health`,
//! `lastDetections`, and `stop` all work on a stopped engine, which is exactly
//! the state the mapping screen writes its polygons in.

use core_ffi::{Engine, EngineError, StreamStatus, TableStatus};

/// Same file the Kotlin contract test validates and the mapping UI writes.
const CONFIG: &str = include_str!("../../../contracts/fixtures/valid/meja-mock.json");

#[test]
fn the_canonical_fixture_installs_as_unknown_tables() {
    let engine = Engine::new();

    engine
        .set_tables(CONFIG.to_string())
        .expect("the canonical fixture must be accepted by the real parser");

    let tables = engine
        .tables()
        .expect("tables are readable while the engine is stopped");
    assert_eq!(tables.len(), 1);
    assert_eq!(tables[0].table_id, "meja-mock");
    assert_eq!(tables[0].label, "Meja Mock");
    assert_eq!(
        tables[0].status,
        TableStatus::Unknown,
        "a map with no frame behind it must never report available"
    );
    assert_eq!(tables[0].persons_in_region, 0);
    assert_eq!(tables[0].confidence, 0.0);

    let health = engine
        .health()
        .expect("health is readable while the engine is stopped");
    assert_eq!(health.stream, StreamStatus::Idle);
    assert_eq!(health.tables, 1);
    assert_eq!(health.tables_unknown, 1);
    assert_eq!(health.tables_available, 0);
    assert_eq!(health.tables_occupied, 0);
    assert!(health.camera_id.is_empty());
    assert!(!health.model_ready);
    assert_eq!(health.frames_in, 0);

    assert!(
        engine
            .last_detections()
            .expect("detections are readable while stopped")
            .is_empty(),
        "the overlay must start empty, not with a stale box"
    );
}

#[test]
fn a_rejected_map_leaves_the_previous_tables_in_place() {
    let engine = Engine::new();
    engine
        .set_tables(CONFIG.to_string())
        .expect("the canonical fixture is valid");

    let error = engine
        .set_tables("{\"version\":2,\"tables\":[".to_string())
        .expect_err("truncated JSON must be rejected");
    assert!(
        matches!(error, EngineError::Tables { .. }),
        "a bad map must report Tables, got {error:?}"
    );

    let tables = engine.tables().expect("tables are readable");
    assert_eq!(
        tables.len(),
        1,
        "a rejected map must not wipe the map the user already saved"
    );
    assert_eq!(tables[0].table_id, "meja-mock");
}

#[test]
fn a_frame_before_start_is_refused_and_invents_no_status() {
    let engine = Engine::new();
    engine
        .set_tables(CONFIG.to_string())
        .expect("the canonical fixture is valid");

    let error = engine
        .push_frame(2, 2, vec![0u8; 12])
        .expect_err("no session is running");
    assert_eq!(error, EngineError::NotRunning);

    let error = engine
        .report_stream_error(StreamStatus::Unreachable)
        .expect_err("a stopped engine cannot report a camera failure");
    assert_eq!(error, EngineError::NotRunning);

    let health = engine.health().expect("health is readable");
    assert_eq!(health.frames_in, 0);
    assert_eq!(
        health.tables_unknown, 1,
        "a refused frame must leave every table unknown"
    );
    assert_eq!(health.tables_available, 0);
}

#[test]
fn stopping_a_stopped_engine_is_a_no_op_that_keeps_the_map() {
    let engine = Engine::new();
    engine
        .set_tables(CONFIG.to_string())
        .expect("the canonical fixture is valid");

    engine.stop().expect("stop on a stopped engine is allowed");
    engine.stop().expect("stop is idempotent");

    let health = engine.health().expect("health is readable");
    assert_eq!(health.stream, StreamStatus::Idle);
    assert_eq!(health.tables, 1);
    assert_eq!(health.tables_available, 0);
    assert_eq!(engine.tables().expect("tables are readable").len(), 1);
}
