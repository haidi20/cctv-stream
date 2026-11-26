//! Embedded contracts and the server-side wire types derived from them.
//!
//! The server never re-declares a schema. Payloads are checked against the same
//! crate the Android app and Rust core use, so a field the client cannot send is
//! rejected here too.

use chrono::{DateTime, SecondsFormat, Utc};
use serde::Serialize;

/// RFC 3339 UTC timestamp used on the wire.
pub(crate) fn now_utc() -> String {
    Utc::now().to_rfc3339_opts(SecondsFormat::Secs, true)
}

/// Parse an RFC 3339 timestamp and return it normalised to UTC whole seconds.
pub(crate) fn parse_utc(value: &str) -> Option<String> {
    DateTime::parse_from_rfc3339(value).ok().map(|parsed| {
        parsed
            .with_timezone(&Utc)
            .to_rfc3339_opts(SecondsFormat::Secs, true)
    })
}

/// `POST /v1/sync` acknowledgement (`contracts/schemas/sync-ack.schema.json`).
#[derive(Debug, Serialize)]
pub struct SyncAck {
    pub device_id: String,
    pub acked_seq: i64,
    pub server_time: String,
    pub duplicates: i64,
}

/// `POST /v1/snapshot` response (`docs/server-api.md` section 6).
#[derive(Debug, Serialize)]
pub struct SnapshotAck {
    pub accepted: bool,
    pub server_time: String,
    pub tables: Vec<SnapshotAckTable>,
}

/// One table entry in the snapshot response.
#[derive(Debug, Serialize)]
pub struct SnapshotAckTable {
    pub table_id: String,
    pub status: String,
    pub source: String,
}

/// `GET /health` response. Aggregates only; no paths or credentials.
#[derive(Debug, Serialize)]
pub struct HealthResponse {
    pub status: &'static str,
    pub version: &'static str,
    pub database: &'static str,
    pub uptime_s: u64,
}

/// WebSocket envelope (`docs/server-api.md` section 7).
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type")]
pub enum WsMessage {
    /// A table transition accepted by `/v1/sync`.
    #[serde(rename = "table_status")]
    TableStatus {
        event_id: i64,
        device_id: String,
        table_id: String,
        status: String,
        since: String,
        server_time: String,
    },
    /// A device sent a heartbeat.
    #[serde(rename = "device_seen")]
    DeviceSeen {
        event_id: i64,
        device_id: String,
        session_id: String,
        server_time: String,
    },
    /// The client asked for a message id the buffer no longer holds.
    #[serde(rename = "resync")]
    Resync { event_id: i64, server_time: String },
}

impl WsMessage {
    /// Event id carried by this message.
    pub fn event_id(&self) -> i64 {
        match self {
            Self::TableStatus { event_id, .. }
            | Self::DeviceSeen { event_id, .. }
            | Self::Resync { event_id, .. } => *event_id,
        }
    }

    /// Replace the event id, used when publishing through the ring buffer.
    pub fn with_event_id(mut self, id: i64) -> Self {
        match &mut self {
            Self::TableStatus { event_id, .. } => *event_id = id,
            Self::DeviceSeen { event_id, .. } => *event_id = id,
            Self::Resync { event_id, .. } => *event_id = id,
        }
        self
    }
}
