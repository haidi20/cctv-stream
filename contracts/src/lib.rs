//! Shared JSON Schema contracts for the Rust core, the Android app, and the sync
//! server.
//!
//! `schemas/` is the single source of truth. There is no hand-written copy in
//! Kotlin or in the server: both sides validate against these documents and the
//! fixtures under `fixtures/`.
//!
//! Validation happens against an in-memory registry (no file or network
//! retrieval), so a missing or mistyped `$ref` fails at [`Contract::new`], not on
//! the first payload. Format assertions are enabled: timestamps must be RFC 3339
//! UTC, which is part of the wire contract.
//!
//! Validation errors never echo payload values. RTSP URLs and credentials must
//! not leak into logs, and a contract error is logged far from the code that
//! knows which fields are secret.

use std::fmt;
use std::sync::OnceLock;

use jsonschema::{Registry, ValidationError, Validator};
use serde_json::Value;
use thiserror::Error;

/// Table configuration v2 (`docs/data-contracts.md` section 1).
pub const TABLE_CONFIG_SCHEMA: &str = include_str!("../schemas/table-config.schema.json");
/// One table status transition (`docs/data-contracts.md` section 2).
pub const TABLE_STATUS_SCHEMA: &str = include_str!("../schemas/table-status.schema.json");
/// Batch sent to `POST /v1/sync` (`docs/data-contracts.md` section 3).
pub const SYNC_REQUEST_SCHEMA: &str = include_str!("../schemas/sync-request.schema.json");
/// Successful response to `POST /v1/sync` (`docs/data-contracts.md` section 6).
pub const SYNC_ACK_SCHEMA: &str = include_str!("../schemas/sync-ack.schema.json");
/// Device heartbeat (`docs/data-contracts.md` section 5).
pub const HEARTBEAT_SCHEMA: &str = include_str!("../schemas/heartbeat.schema.json");

/// Which contract a payload is checked against.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Contract {
    /// Table configuration document.
    TableConfig,
    /// Table status event.
    TableStatus,
    /// Sync request body.
    SyncRequest,
    /// Sync acknowledgement body.
    SyncAck,
    /// Heartbeat body.
    Heartbeat,
}

impl Contract {
    /// Every contract, in documentation order.
    pub const ALL: [Self; 5] = [
        Self::TableConfig,
        Self::TableStatus,
        Self::SyncRequest,
        Self::SyncAck,
        Self::Heartbeat,
    ];

    /// Schema file name, used in logs and test names.
    pub const fn schema_file(self) -> &'static str {
        match self {
            Self::TableConfig => "table-config.schema.json",
            Self::TableStatus => "table-status.schema.json",
            Self::SyncRequest => "sync-request.schema.json",
            Self::SyncAck => "sync-ack.schema.json",
            Self::Heartbeat => "heartbeat.schema.json",
        }
    }

    /// Schema document as embedded in the binary.
    pub const fn schema_json(self) -> &'static str {
        match self {
            Self::TableConfig => TABLE_CONFIG_SCHEMA,
            Self::TableStatus => TABLE_STATUS_SCHEMA,
            Self::SyncRequest => SYNC_REQUEST_SCHEMA,
            Self::SyncAck => SYNC_ACK_SCHEMA,
            Self::Heartbeat => HEARTBEAT_SCHEMA,
        }
    }
}

impl fmt::Display for Contract {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.schema_file())
    }
}

/// Why a payload was rejected.
///
/// The display form carries a JSON Pointer and a keyword, never a payload value.
#[derive(Debug, Error)]
pub enum ContractError {
    /// The schema document itself could not be compiled.
    #[error("schema '{contract}' is invalid: {detail}")]
    InvalidSchema {
        /// Contract whose schema failed to compile.
        contract: Contract,
        /// First compilation error, with instance values masked.
        detail: String,
    },
    /// The payload does not match the schema.
    #[error("payload does not match '{contract}' at '{pointer}': {detail}")]
    InvalidPayload {
        /// Contract the payload was checked against.
        contract: Contract,
        /// JSON Pointer to the first offending value.
        pointer: String,
        /// First validation error, with instance values masked.
        detail: String,
    },
    /// The document was not valid JSON.
    #[error("payload is not valid JSON: {0}")]
    MalformedJson(String),
}

/// Compile every schema once, offline.
///
/// The registry resolves `$ref`s between the five documents only. Anything else
/// is a schema bug and surfaces as [`ContractError::InvalidSchema`].
fn compiled() -> Result<&'static [Validator], ContractError> {
    static COMPILED: OnceLock<Result<Vec<Validator>, (Contract, String)>> = OnceLock::new();

    COMPILED
        .get_or_init(build_validators)
        .as_deref()
        .map_err(|(contract, detail)| ContractError::InvalidSchema {
            contract: *contract,
            detail: detail.clone(),
        })
}

fn build_validators() -> Result<Vec<Validator>, (Contract, String)> {
    let documents = Contract::ALL
        .iter()
        .map(|contract| {
            serde_json::from_str::<Value>(contract.schema_json())
                .map_err(|error| (*contract, error.to_string()))
        })
        .collect::<Result<Vec<_>, _>>()?;

    let mut builder = Registry::new();
    for (contract, document) in Contract::ALL.iter().zip(&documents) {
        builder = builder
            .add(contract.schema_file(), document)
            .map_err(|error| (*contract, error.to_string()))?;
    }
    let registry = builder
        .prepare()
        .map_err(|error| (Contract::TableConfig, error.to_string()))?;

    Contract::ALL
        .iter()
        .zip(&documents)
        .map(|(contract, document)| {
            jsonschema::draft202012::options()
                .with_registry(&registry)
                .should_validate_formats(true)
                .offline()
                .build(document)
                .map_err(|error| (*contract, error.masked().to_string()))
        })
        .collect()
}

fn validator_for(contract: Contract) -> Result<&'static Validator, ContractError> {
    let index = Contract::ALL
        .iter()
        .position(|candidate| *candidate == contract)
        .expect("Contract::ALL lists every variant");
    Ok(&compiled()?[index])
}

/// Check a parsed JSON value against `contract`.
///
/// # Errors
///
/// Returns [`ContractError::InvalidPayload`] on the first violation, with the
/// offending value masked.
pub fn validate(contract: Contract, payload: &Value) -> Result<(), ContractError> {
    let validator = validator_for(contract)?;
    let mut errors = validator.iter_errors(payload);
    match errors.next() {
        None => Ok(()),
        Some(error) => Err(invalid_payload(contract, &error)),
    }
}

/// Every violation, in the validator's deterministic order.
///
/// [`validate`] reports only the first one, which depends on property order and
/// can therefore be a consequence rather than the cause (a version 1 document
/// with pixel coordinates fails on `/version` *and* on every coordinate).
/// Callers that need to explain a rejection use this and look for the root
/// cause. Only the first [`MAX_REPORTED_ERRORS`] violations are returned so a
/// hostile payload cannot turn one rejection into unbounded work.
///
/// # Errors
///
/// Returns [`ContractError::InvalidSchema`] only when the schemas cannot be
/// compiled; an empty vector means the payload is valid.
pub fn violations(contract: Contract, payload: &Value) -> Result<Vec<Violation>, ContractError> {
    let validator = validator_for(contract)?;
    Ok(validator
        .iter_errors(payload)
        .take(MAX_REPORTED_ERRORS)
        .map(|error| Violation {
            pointer: location_or_root(error.instance_path()),
            detail: error.masked().to_string(),
        })
        .collect())
}

/// Maximum number of violations returned by [`violations`].
pub const MAX_REPORTED_ERRORS: usize = 20;

/// One schema violation with the payload value masked.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Violation {
    /// JSON Pointer to the offending value; `/` for the document root.
    pub pointer: String,
    /// Masked description of the failed keyword.
    pub detail: String,
}

fn invalid_payload(contract: Contract, error: &ValidationError<'_>) -> ContractError {
    ContractError::InvalidPayload {
        contract,
        pointer: location_or_root(error.instance_path()),
        detail: error.masked().to_string(),
    }
}

fn location_or_root(location: &jsonschema::paths::Location) -> String {
    let pointer = location.as_str();
    if pointer.is_empty() {
        "/".to_string()
    } else {
        pointer.to_string()
    }
}

/// Parse a JSON document and validate it in one step.
///
/// # Errors
///
/// Returns [`ContractError::MalformedJson`] when the text is not JSON, or
/// [`ContractError::InvalidPayload`] when it does not match `contract`.
pub fn validate_str(contract: Contract, json: &str) -> Result<(), ContractError> {
    let payload: Value = serde_json::from_str(json)
        .map_err(|error| ContractError::MalformedJson(error.to_string()))?;
    validate(contract, &payload)
}

/// Validate a table configuration document.
///
/// # Errors
///
/// See [`validate`].
pub fn validate_table_config(payload: &Value) -> Result<(), ContractError> {
    validate(Contract::TableConfig, payload)
}

/// Validate a table status event.
///
/// # Errors
///
/// See [`validate`].
pub fn validate_table_status(payload: &Value) -> Result<(), ContractError> {
    validate(Contract::TableStatus, payload)
}

/// Validate a sync request body.
///
/// # Errors
///
/// See [`validate`].
pub fn validate_sync_request(payload: &Value) -> Result<(), ContractError> {
    validate(Contract::SyncRequest, payload)
}

/// Validate a sync acknowledgement body.
///
/// # Errors
///
/// See [`validate`].
pub fn validate_sync_ack(payload: &Value) -> Result<(), ContractError> {
    validate(Contract::SyncAck, payload)
}

/// Validate a heartbeat body.
///
/// # Errors
///
/// See [`validate`].
pub fn validate_heartbeat(payload: &Value) -> Result<(), ContractError> {
    validate(Contract::Heartbeat, payload)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_schema_compiles_offline() {
        assert_eq!(
            compiled().expect("schemas compile").len(),
            Contract::ALL.len()
        );
    }

    #[test]
    fn rejects_malformed_json_without_masking_the_parser_message() {
        let error = validate_str(Contract::TableStatus, "{").expect_err("must fail");
        assert!(matches!(error, ContractError::MalformedJson(_)));
    }

    #[test]
    fn invalid_payload_does_not_echo_the_value() {
        let payload = serde_json::json!({
            "table_id": "meja-01",
            "status": "rtsp://admin:hunter2@10.0.0.5/stream",
            "since": "2026-11-25T05:31:00Z"
        });
        let error = validate_table_status(&payload).expect_err("must fail");
        let message = error.to_string();
        assert!(message.contains("/status"), "{message}");
        assert!(!message.contains("hunter2"), "{message}");
    }

    #[test]
    fn each_schema_rejects_a_payload_that_breaks_its_own_rules() {
        // Guards against a schema that compiles but accepts anything, e.g. after
        // `additionalProperties` or a `$ref` is mistyped.
        let cases = [
            (
                Contract::TableConfig,
                serde_json::json!({
                    "version": 2,
                    "video": { "width": 0, "height": 720, "aspect_ratio": "16:9" },
                    "tables": []
                }),
            ),
            (
                Contract::TableStatus,
                serde_json::json!({
                    "table_id": "meja-01",
                    "status": "available",
                    "since": "2026-11-25T05:31:00Z",
                    "confidence": 1.5
                }),
            ),
            (
                Contract::SyncRequest,
                serde_json::json!({
                    "device_id": "android-warung-01",
                    "seq": 1,
                    "sent_at": "2026-11-25T05:31:05Z",
                    "events": [{ "table_id": "meja-01", "status": "free", "since": "2026-11-25T05:31:00Z" }]
                }),
            ),
            (
                Contract::SyncAck,
                serde_json::json!({
                    "device_id": "android-warung-01",
                    "acked_seq": 1,
                    "server_time": "2026-11-25T05:31:06"
                }),
            ),
            (
                Contract::Heartbeat,
                serde_json::json!({
                    "device_id": "android-warung-01",
                    "session_id": "3f0c9a1e",
                    "sent_at": "2026-11-25T05:31:05Z",
                    "stream": { "status": "connected" },
                    "device": { "profile": "balanced" },
                    "runtime": { "model_ready": "yes" }
                }),
            ),
        ];

        for (contract, payload) in cases {
            assert!(
                validate(contract, &payload).is_err(),
                "{contract} accepted a payload that breaks its own rules"
            );
        }
    }

    #[test]
    fn timestamps_must_be_utc() {
        let payload = serde_json::json!({
            "table_id": "meja-01",
            "status": "available",
            "since": "2026-11-25T05:31:00+07:00"
        });
        assert!(validate_table_status(&payload).is_err());
    }
}
