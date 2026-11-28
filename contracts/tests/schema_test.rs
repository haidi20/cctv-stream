//! Every fixture is checked against the schema it names.
//!
//! Fixture naming rule: `<schema>.<case>.json`. The part before the first dot
//! selects the contract, so a new case cannot be silently skipped by a typo in a
//! hand-written list.

use std::fs;
use std::path::{Path, PathBuf};

use contracts::{validate_str, Contract, ContractError};

fn fixtures_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures")
}

/// Maps `<schema>.<case>.json` (and the `meja-*` table-config fixtures) to a
/// contract.
fn contract_for(file_name: &str) -> Contract {
    let stem = file_name.strip_suffix(".json").unwrap_or(file_name);
    let prefix = stem.split('.').next().unwrap_or(stem);

    // `meja-*` names predate the `<schema>.<case>` rule and are all table
    // configurations. Without this they would panic as an unknown schema.
    if prefix.starts_with("meja-") {
        return Contract::TableConfig;
    }

    Contract::ALL
        .into_iter()
        .find(|contract| {
            contract
                .schema_file()
                .strip_suffix(".schema.json")
                .is_some_and(|name| name == prefix)
        })
        .unwrap_or_else(|| panic!("{file_name} does not name a known schema"))
}

fn json_files(directory: &Path) -> Vec<PathBuf> {
    let mut files: Vec<PathBuf> = fs::read_dir(directory)
        .unwrap_or_else(|error| panic!("cannot read {}: {error}", directory.display()))
        .map(|entry| entry.expect("readable entry").path())
        .filter(|path| path.extension().is_some_and(|ext| ext == "json"))
        .collect();
    files.sort();
    files
}

fn read(path: &Path) -> String {
    fs::read_to_string(path)
        .unwrap_or_else(|error| panic!("cannot read {}: {error}", path.display()))
}

#[test]
fn every_valid_fixture_matches_its_schema() {
    let directory = fixtures_root().join("valid");
    let files = json_files(&directory);
    assert!(!files.is_empty(), "no valid fixtures found");

    for path in files {
        let name = path.file_name().unwrap().to_string_lossy().into_owned();
        let contract = contract_for(&name);
        if let Err(error) = validate_str(contract, &read(&path)) {
            panic!("{name} should be valid for {contract}: {error}");
        }
    }
}

#[test]
fn every_invalid_fixture_is_rejected_by_its_schema() {
    let directory = fixtures_root().join("invalid");
    let files = json_files(&directory);
    assert!(!files.is_empty(), "no invalid fixtures found");

    for path in files {
        let name = path.file_name().unwrap().to_string_lossy().into_owned();
        let contract = contract_for(&name);
        match validate_str(contract, &read(&path)) {
            Err(ContractError::InvalidPayload {
                contract: actual, ..
            }) => {
                assert_eq!(actual, contract, "{name} reported the wrong contract");
            }
            Err(other) => panic!("{name} should fail schema validation, got: {other}"),
            Ok(()) => panic!("{name} should be rejected by {contract}"),
        }
    }
}

fn expect_rejected(name: &str, pointer: &str) {
    let path = fixtures_root().join("invalid").join(name);
    let contract = contract_for(name);
    match validate_str(contract, &read(&path)) {
        Err(ContractError::InvalidPayload {
            pointer: actual, ..
        }) => assert_eq!(actual, pointer, "{name} failed at the wrong location"),
        other => panic!("{name} should be rejected at {pointer}, got: {other:?}"),
    }
}

#[test]
fn rejects_polygon_with_two_points() {
    expect_rejected("table-config.polygon-two-points.json", "/tables/0/polygon");
}

#[test]
fn rejects_unknown_table_status() {
    expect_rejected("table-status.unknown-status.json", "/status");
}

#[test]
fn rejects_negative_seq() {
    expect_rejected("sync-request.negative-seq.json", "/seq");
}

#[test]
fn rejects_heartbeat_with_an_image_field() {
    expect_rejected("heartbeat.image-field.json", "/");
}

#[test]
fn rejects_table_config_version_newer_than_supported() {
    expect_rejected("table-config.version-three.json", "/version");
}

#[test]
fn rejects_pixel_coordinates_from_version_one() {
    // Two independent violations: the version and every out-of-range pixel.
    // `validate` reports only the first, so this pins the full set instead.
    let path = fixtures_root()
        .join("invalid")
        .join("table-config.pixel-version.json");
    let payload: serde_json::Value =
        serde_json::from_str(&read(&path)).expect("fixture is valid JSON");
    let violations =
        contracts::violations(Contract::TableConfig, &payload).expect("schema compiles");
    let pointers: Vec<&str> = violations.iter().map(|v| v.pointer.as_str()).collect();

    assert!(pointers.contains(&"/version"), "{pointers:?}");
    assert!(pointers.contains(&"/tables/0/polygon/0/x"), "{pointers:?}");
}

#[test]
fn rejects_rtsp_url_smuggled_into_a_status_event() {
    expect_rejected("table-status.rtsp-url-field.json", "/");
}

#[test]
fn rejects_non_utc_timestamps() {
    expect_rejected("table-status.local-offset-timestamp.json", "/since");
}

#[test]
fn rejects_an_empty_sync_batch() {
    expect_rejected("sync-request.empty-events.json", "/events");
}

#[test]
fn rejects_a_snapshot_without_tables() {
    expect_rejected("snapshot-request.empty-tables.json", "/tables");
}

#[test]
fn a_sync_request_is_not_a_valid_snapshot() {
    // The two envelopes share a device id and a timestamp, so a schema that
    // forgot `tables` would accept the wrong document silently.
    let path = fixtures_root().join("valid").join("sync-request.json");
    let payload: serde_json::Value =
        serde_json::from_str(&read(&path)).expect("fixture is valid JSON");
    assert!(contracts::validate(Contract::SnapshotRequest, &payload).is_err());
}

#[test]
fn rejects_a_negative_acknowledgement() {
    expect_rejected("sync-ack.negative-acked-seq.json", "/acked_seq");
}

#[test]
fn rejects_a_frame_nested_in_the_stream_block() {
    expect_rejected("heartbeat.nested-frame-field.json", "/stream");
}

#[test]
fn rejects_an_unknown_device_profile() {
    expect_rejected("heartbeat.unknown-profile.json", "/device/profile");
}

#[test]
fn rejects_a_heartbeat_without_runtime_state() {
    expect_rejected("heartbeat.missing-runtime.json", "/");
}

#[test]
fn rejects_idle_as_a_last_error() {
    expect_rejected("heartbeat.idle-last-error.json", "/stream/last_error");
}

#[test]
fn every_schema_is_a_valid_draft_2020_12_document() {
    for contract in Contract::ALL {
        let schema: serde_json::Value =
            serde_json::from_str(contract.schema_json()).expect("schema is valid JSON");
        jsonschema::meta::validate(&schema)
            .unwrap_or_else(|error| panic!("{contract} is not a valid schema: {}", error.masked()));
    }
}

#[test]
fn a_valid_fixture_does_not_validate_against_the_wrong_contract() {
    // Regression against a schema that silently accepts anything: the sync
    // request fixture must be rejected by the heartbeat contract.
    let path = fixtures_root().join("valid").join("sync-request.json");
    let payload: serde_json::Value =
        serde_json::from_str(&read(&path)).expect("fixture is valid JSON");
    assert!(contracts::validate(Contract::Heartbeat, &payload).is_err());
    assert!(contracts::validate(Contract::SyncAck, &payload).is_err());
}

#[test]
fn canonical_table_config_fixture_matches_the_rust_loader_contract() {
    let path = fixtures_root().join("valid").join("meja-mock.json");
    validate_str(Contract::TableConfig, &read(&path)).expect("canonical fixture must validate");
}

#[test]
fn every_contract_has_a_valid_fixture() {
    for contract in Contract::ALL {
        let has_fixture = json_files(&fixtures_root().join("valid"))
            .iter()
            .any(|path| {
                let name = path.file_name().unwrap().to_string_lossy();
                contract_for(&name) == contract
            });
        assert!(has_fixture, "no valid fixture for {contract}");
    }
}

#[test]
fn every_contract_has_an_invalid_fixture() {
    for contract in Contract::ALL {
        let has_fixture = json_files(&fixtures_root().join("invalid"))
            .iter()
            .any(|path| {
                let name = path.file_name().unwrap().to_string_lossy();
                contract_for(&name) == contract
            });
        assert!(has_fixture, "no invalid fixture for {contract}");
    }
}
