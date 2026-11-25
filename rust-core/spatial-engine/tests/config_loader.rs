//! Configuration contract enforcement.

use spatial_engine::{ConfigError, TableConfigSet};

const VALID: &str = include_str!("../../../contracts/fixtures/valid/meja-mock.json");

#[test]
fn canonical_fixture_loads() {
    let config = TableConfigSet::from_json(VALID).unwrap();
    assert_eq!(config.len(), 1);
    assert_eq!(config.table("meja-mock").unwrap().label, "Meja Mock");
}

#[test]
fn version_one_is_rejected_with_a_migration_error() {
    let json = VALID.replace("\"version\": 2", "\"version\": 1");
    assert_eq!(
        TableConfigSet::from_json(&json),
        Err(ConfigError::LegacyPixelVersion)
    );
}

#[test]
fn unknown_version_is_rejected() {
    let json = VALID.replace("\"version\": 2", "\"version\": 3");
    assert_eq!(
        TableConfigSet::from_json(&json),
        Err(ConfigError::UnsupportedVersion { version: 3 })
    );
}

#[test]
fn malformed_json_reports_json_error() {
    assert!(matches!(
        TableConfigSet::from_json("{ not json"),
        Err(ConfigError::Json(_))
    ));
}

#[test]
fn missing_aspect_ratio_is_rejected() {
    let json = r#"{
      "version": 2,
      "video": { "width": 1280, "height": 720 },
      "tables": [
        { "id": "a", "polygon": [{ "x": 0.1, "y": 0.1 }, { "x": 0.3, "y": 0.1 }, { "x": 0.3, "y": 0.3 }] }
      ]
    }"#;
    assert!(matches!(
        TableConfigSet::from_json(json),
        Err(ConfigError::Json(_))
    ));
}
