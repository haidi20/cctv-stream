//! End-to-end tests through the real router and a real SQLite file.
//!
//! Every test enrols a device with a known token, so a regression in auth or in
//! the `seq` rules fails here rather than in production. No network listener is
//! opened: `tower::ServiceExt::oneshot` drives the same handler stack.

use axum::body::Body;
use axum::http::{header, Request, StatusCode};
use cctv_server::auth::hash_token;
use cctv_server::config::Config;
use cctv_server::state::AppState;
use cctv_server::store::Db;
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tower::ServiceExt;
use tracing_subscriber::EnvFilter;

const DEVICE: &str = "android-warung-01";
const TOKEN: &str = "ctv_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

struct Harness {
    app: axum::Router,
    state: AppState,
    _dir: tempfile::TempDir,
}

impl Harness {
    async fn new() -> Self {
        Self::with_limits(60, 4, 2).await
    }

    async fn with_limits(sync: u32, heartbeat: u32, snapshot: u32) -> Self {
        let _ = tracing_subscriber::fmt()
            .with_env_filter(EnvFilter::new("warn"))
            .try_init();

        let dir = tempfile::tempdir().expect("temp dir");
        let db = Db::open(&dir.path().join("test.db"))
            .await
            .expect("open database");
        db.create_device(DEVICE, &hash_token(TOKEN))
            .await
            .expect("enrol device");

        let config = Config {
            bind: "127.0.0.1:0".parse().expect("socket"),
            database_path: dir.path().join("test.db"),
            max_body_bytes: 64 * 1024,
            sync_per_minute: sync,
            heartbeat_per_minute: heartbeat,
            snapshot_per_minute: snapshot,
        };
        let state = AppState::new(db, config, "test");
        let app = cctv_server::build_app(state.clone());
        Self {
            app,
            state,
            _dir: dir,
        }
    }

    async fn request(
        &self,
        method: &str,
        uri: &str,
        token: Option<&str>,
        body: Option<Value>,
    ) -> (StatusCode, Value) {
        let mut builder = Request::builder().method(method).uri(uri);
        if let Some(token) = token {
            builder = builder.header(header::AUTHORIZATION, format!("Bearer {token}"));
        }
        let request = match body {
            Some(body) => builder
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(body.to_string()))
                .expect("request"),
            None => builder.body(Body::empty()).expect("request"),
        };
        self.send(request).await
    }

    async fn send(&self, request: Request<Body>) -> (StatusCode, Value) {
        let response = self
            .app
            .clone()
            .oneshot(request)
            .await
            .expect("router responds");
        let status = response.status();
        let body = response
            .into_body()
            .collect()
            .await
            .expect("body")
            .to_bytes();
        let value = if body.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&body).unwrap_or(Value::Null)
        };
        (status, value)
    }
}

fn sync_batch(seq: i64, events: Value) -> Value {
    json!({
        "device_id": DEVICE,
        "seq": seq,
        "sent_at": "2026-11-25T05:31:05Z",
        "events": events
    })
}

fn event(table: &str, status: &str) -> Value {
    json!({
        "table_id": table,
        "status": status,
        "since": "2026-11-25T05:31:00Z"
    })
}

#[tokio::test]
async fn accepts_a_contiguous_batch_and_acks_after_commit() {
    let harness = Harness::new().await;
    let body = sync_batch(
        3,
        json!([
            event("meja-01", "occupied"),
            event("meja-02", "available"),
            event("meja-03", "unknown")
        ]),
    );
    let (status, ack) = harness
        .request("POST", "/v1/sync", Some(TOKEN), Some(body))
        .await;

    assert_eq!(status, StatusCode::OK, "{ack}");
    assert_eq!(ack["acked_seq"], 3);
    assert_eq!(ack["duplicates"], 0);
    assert_eq!(ack["device_id"], DEVICE);
    assert_eq!(harness.state.store.acked_seq(DEVICE).await.unwrap(), 3);
    assert_eq!(harness.state.store.count_events(DEVICE).await.unwrap(), 3);
}

#[tokio::test]
async fn replaying_a_batch_is_idempotent_and_reports_duplicates() {
    let harness = Harness::new().await;
    let body = sync_batch(
        2,
        json!([event("meja-01", "occupied"), event("meja-02", "unknown")]),
    );
    let (first, _) = harness
        .request("POST", "/v1/sync", Some(TOKEN), Some(body.clone()))
        .await;
    let (second, ack) = harness
        .request("POST", "/v1/sync", Some(TOKEN), Some(body))
        .await;

    assert_eq!(first, StatusCode::OK);
    assert_eq!(second, StatusCode::OK);
    assert_eq!(ack["duplicates"], 2);
    assert_eq!(harness.state.store.count_events(DEVICE).await.unwrap(), 2);
}

#[tokio::test]
async fn reports_a_gap_with_expected_seq() {
    let harness = Harness::new().await;
    let body = sync_batch(5, json!([event("meja-01", "occupied")]));
    let (status, error) = harness
        .request("POST", "/v1/sync", Some(TOKEN), Some(body))
        .await;

    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(error["error"]["code"], "seq_gap");
    assert_eq!(error["expected_seq"], 1);
    assert!(error["error"]["request_id"].as_str().is_some());
}

#[tokio::test]
async fn rejects_a_batch_whose_seq_precedes_one() {
    let harness = Harness::new().await;
    let body = sync_batch(0, json!([event("meja-01", "occupied")]));
    let (status, error) = harness
        .request("POST", "/v1/sync", Some(TOKEN), Some(body))
        .await;

    assert_eq!(status, StatusCode::CONFLICT);
    assert_eq!(error["error"]["code"], "seq_gap");
}

#[tokio::test]
async fn rejects_oversized_batches() {
    let harness = Harness::new().await;
    let events: Vec<Value> = (0..101)
        .map(|index| event(&format!("meja-{index}"), "available"))
        .collect();
    let body = sync_batch(101, Value::Array(events));
    let (status, error) = harness
        .request("POST", "/v1/sync", Some(TOKEN), Some(body))
        .await;

    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(error["error"]["code"], "payload_too_large");
}

#[tokio::test]
async fn rejects_an_unknown_field_such_as_an_rtsp_url() {
    let harness = Harness::new().await;
    let body = json!({
        "device_id": DEVICE,
        "seq": 1,
        "sent_at": "2026-11-25T05:31:05Z",
        "events": [{
            "table_id": "meja-01",
            "status": "occupied",
            "since": "2026-11-25T05:31:00Z",
            "rtsp_url": "rtsp://admin:hunter2@10.0.0.5/stream"
        }]
    });
    let (status, error) = harness
        .request("POST", "/v1/sync", Some(TOKEN), Some(body))
        .await;

    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(error["error"]["code"], "invalid_payload");
    let text = error.to_string();
    assert!(!text.contains("hunter2"), "credential leaked: {text}");
}

#[tokio::test]
async fn rejects_a_malformed_json_body() {
    let harness = Harness::new().await;
    let request = Request::builder()
        .method("POST")
        .uri("/v1/sync")
        .header(header::AUTHORIZATION, format!("Bearer {TOKEN}"))
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from("{"))
        .expect("request");
    let (status, error) = harness.send(request).await;

    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(error["error"]["code"], "invalid_payload");
}

#[tokio::test]
async fn missing_or_bad_tokens_are_unauthorized() {
    let harness = Harness::new().await;
    let body = sync_batch(1, json!([event("meja-01", "available")]));

    let (missing, _) = harness
        .request("POST", "/v1/sync", None, Some(body.clone()))
        .await;
    let (bad, _) = harness
        .request("POST", "/v1/sync", Some("ctv_deadbeef"), Some(body))
        .await;

    assert_eq!(missing, StatusCode::UNAUTHORIZED);
    assert_eq!(bad, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn a_token_cannot_write_for_another_device() {
    let harness = Harness::new().await;
    let body = json!({
        "device_id": "someone-else",
        "seq": 1,
        "sent_at": "2026-11-25T05:31:05Z",
        "events": [event("meja-01", "available")]
    });
    let (status, error) = harness
        .request("POST", "/v1/sync", Some(TOKEN), Some(body))
        .await;

    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(error["error"]["code"], "forbidden");
}

#[tokio::test]
async fn every_response_carries_a_request_id() {
    let harness = Harness::new().await;
    let request = Request::builder()
        .method("GET")
        .uri("/health")
        .body(Body::empty())
        .expect("request");
    let response = harness
        .app
        .clone()
        .oneshot(request)
        .await
        .expect("router responds");

    assert!(response.headers().contains_key("x-request-id"));
}

#[tokio::test]
async fn health_is_open_and_reports_the_database() {
    let harness = Harness::new().await;
    let (status, body) = harness.request("GET", "/health", None, None).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "ok");
    assert_eq!(body["database"], "ok");
}

#[tokio::test]
async fn stores_a_valid_heartbeat() {
    let harness = Harness::new().await;
    let body = json!({
        "device_id": DEVICE,
        "session_id": "3f0c9a1e",
        "sent_at": "2026-11-25T05:31:05Z",
        "stream": { "status": "connected" },
        "device": { "profile": "balanced" },
        "runtime": { "model_ready": true }
    });
    let (status, response) = harness
        .request("POST", "/v1/heartbeat", Some(TOKEN), Some(body))
        .await;

    assert_eq!(status, StatusCode::OK, "{response}");
    assert_eq!(response["accepted"], true);
    assert_eq!(
        harness.state.store.count_heartbeats(DEVICE).await.unwrap(),
        1
    );
}

#[tokio::test]
async fn rejects_forbidden_fields_in_a_heartbeat() {
    let harness = Harness::new().await;
    let body = json!({
        "device_id": DEVICE,
        "session_id": "3f0c9a1e",
        "sent_at": "2026-11-25T05:31:05Z",
        "stream": { "status": "connected" },
        "device": { "profile": "balanced" },
        "runtime": { "model_ready": true },
        "image": "aGVsbG8="
    });
    let (status, error) = harness
        .request("POST", "/v1/heartbeat", Some(TOKEN), Some(body))
        .await;

    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(error["error"]["code"], "invalid_payload");
    assert_eq!(
        harness.state.store.count_heartbeats(DEVICE).await.unwrap(),
        0
    );
}

#[tokio::test]
async fn a_snapshot_keeps_history_and_records_conflicts() {
    let harness = Harness::new().await;
    let synced = sync_batch(1, json!([event("meja-01", "occupied")]));
    let _ = harness
        .request("POST", "/v1/sync", Some(TOKEN), Some(synced))
        .await;

    let snapshot = json!({
        "device_id": DEVICE,
        "sent_at": "2026-11-25T05:35:00Z",
        "tables": [
            { "table_id": "meja-01", "status": "available", "since": "2026-11-25T05:34:00Z" },
            { "table_id": "meja-02", "status": "unknown", "since": "2026-11-25T05:34:00Z" }
        ]
    });
    let (status, response) = harness
        .request("POST", "/v1/snapshot", Some(TOKEN), Some(snapshot))
        .await;

    assert_eq!(status, StatusCode::OK, "{response}");
    assert_eq!(response["accepted"], true);
    assert_eq!(harness.state.store.count_events(DEVICE).await.unwrap(), 1);
    assert_eq!(
        harness.state.store.count_conflicts(DEVICE).await.unwrap(),
        1
    );
    assert_eq!(
        harness
            .state
            .store
            .table_status(DEVICE, "meja-01")
            .await
            .unwrap()
            .as_deref(),
        Some("available")
    );
}

#[tokio::test]
async fn rate_limit_returns_retry_after() {
    let harness = Harness::with_limits(1, 4, 2).await;
    let body = sync_batch(1, json!([event("meja-01", "available")]));
    let (first, _) = harness
        .request("POST", "/v1/sync", Some(TOKEN), Some(body.clone()))
        .await;
    assert_eq!(first, StatusCode::OK);

    let request = Request::builder()
        .method("POST")
        .uri("/v1/sync")
        .header(header::AUTHORIZATION, format!("Bearer {TOKEN}"))
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(body.to_string()))
        .expect("request");
    let response = harness
        .app
        .clone()
        .oneshot(request)
        .await
        .expect("router responds");
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    assert!(response.headers().contains_key(header::RETRY_AFTER));
}

#[tokio::test]
async fn the_ws_buffer_replays_and_falls_back_to_resync() {
    let harness = Harness::new().await;
    let body = sync_batch(1, json!([event("meja-01", "occupied")]));
    let _ = harness
        .request("POST", "/v1/sync", Some(TOKEN), Some(body))
        .await;

    let replay = harness.state.replay_since(0).expect("buffer available");
    assert_eq!(replay.len(), 1);
    assert_eq!(replay[0].event_id(), 1);

    // A client with nothing seen yet asks from 0 and gets the stored message.
    // An id that predates the buffer head after overflow asks for `resync`.
    assert!(harness.state.replay_since(-1).is_none());
}

#[tokio::test]
async fn stored_payload_never_contains_the_token() {
    let harness = Harness::new().await;
    let body = json!({
        "device_id": DEVICE,
        "session_id": "3f0c9a1e",
        "sent_at": "2026-11-25T05:31:05Z",
        "stream": { "status": "connected" },
        "device": { "profile": "balanced" },
        "runtime": { "model_ready": true }
    });
    let _ = harness
        .request("POST", "/v1/heartbeat", Some(TOKEN), Some(body))
        .await;

    // The database stores the body the client sent, and the token travels only
    // in the header. A regression that copies the header into the payload would
    // make this fail.
    let row = sqlx::query("SELECT payload FROM heartbeats LIMIT 1")
        .fetch_one(harness.state.store.pool_for_tests())
        .await
        .expect("row");
    let payload: String = sqlx::Row::get(&row, "payload");
    assert!(!payload.contains("ctv_"), "token leaked into payload");
}
