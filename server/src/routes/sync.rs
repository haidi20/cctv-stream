//! `POST /v1/sync` (`docs/server-api.md` section 4).

use axum::body::Bytes;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::{Extension, Json};
use contracts::Contract;
use serde_json::Value;

use crate::auth::DeviceAuth;
use crate::error::{ApiError, ErrorCode, RequestId};
use crate::model::{now_utc, SyncAck, WsMessage};
use crate::ratelimit::{LimitDecision, Route};
use crate::state::AppState;
use crate::store::{SyncEvent, SyncOutcome};

/// Maximum events in one batch, mirrored from the shared schema.
pub const MAX_EVENTS: usize = 100;

/// Handle one batch.
pub async fn sync(
    State(state): State<AppState>,
    Extension(request_id): Extension<RequestId>,
    auth: DeviceAuth,
    body: Bytes,
) -> Result<impl IntoResponse, ApiError> {
    let request_id = request_id.0;
    enforce_rate_limit(&state, &auth.device_id, Route::Sync, &request_id)?;

    // The count limit is checked before schema validation so an oversized batch
    // is a 413 the client can split, not a generic 400 it would retry verbatim.
    let unvalidated: Value = serde_json::from_slice(&body)
        .map_err(|_| invalid("body is not valid JSON", &request_id))?;
    let declared_events = unvalidated
        .get("events")
        .and_then(Value::as_array)
        .map(Vec::len)
        .unwrap_or(0);
    if declared_events > MAX_EVENTS {
        return Err(ApiError::new(
            ErrorCode::PayloadTooLarge,
            "batch exceeds the event limit",
            request_id,
        ));
    }
    contracts::validate(Contract::SyncRequest, &unvalidated)
        .map_err(|_| invalid("body does not match the contract", &request_id))?;
    let payload = unvalidated;

    let device_id = payload
        .get("device_id")
        .and_then(Value::as_str)
        .ok_or_else(|| invalid("missing device_id", &request_id))?;
    if device_id != auth.device_id {
        return Err(ApiError::new(
            ErrorCode::Forbidden,
            "token does not own this device",
            request_id,
        ));
    }

    let seq = payload
        .get("seq")
        .and_then(Value::as_i64)
        .ok_or_else(|| invalid("missing seq", &request_id))?;
    let sent_at = payload
        .get("sent_at")
        .and_then(Value::as_str)
        .ok_or_else(|| invalid("missing sent_at", &request_id))?;

    let events = payload
        .get("events")
        .and_then(Value::as_array)
        .ok_or_else(|| invalid("missing events", &request_id))?;
    let mut rows = Vec::with_capacity(events.len());
    for event in events {
        let table_id = event
            .get("table_id")
            .and_then(Value::as_str)
            .ok_or_else(|| invalid("missing table_id", &request_id))?;
        let status = event
            .get("status")
            .and_then(Value::as_str)
            .ok_or_else(|| invalid("missing status", &request_id))?;
        let since = event
            .get("since")
            .and_then(Value::as_str)
            .ok_or_else(|| invalid("missing since", &request_id))?;
        rows.push(SyncEvent {
            table_id: table_id.to_string(),
            status: status.to_string(),
            since: since.to_string(),
            confidence: event.get("confidence").and_then(Value::as_f64),
            persons_in_region: event.get("persons_in_region").and_then(Value::as_i64),
        });
    }

    let (outcome, broadcasts) = state
        .store
        .insert_sync_batch(&auth.device_id, seq, sent_at, &rows)
        .await
        .map_err(|error| ApiError::internal(&request_id, &error))?;

    match outcome {
        SyncOutcome::Gap { expected_seq } => Err(ApiError::new(
            ErrorCode::SeqGap,
            format!("expected seq {expected_seq}"),
            request_id,
        )
        .with_expected_seq(expected_seq)),
        SyncOutcome::InvalidSequence => Err(ApiError::new(
            ErrorCode::SeqGap,
            "seq does not describe a valid batch",
            request_id,
        )
        .with_expected_seq(1)),
        SyncOutcome::Accepted { duplicates } => {
            let server_time = now_utc();
            for event in broadcasts {
                state.publish(WsMessage::TableStatus {
                    event_id: 0,
                    device_id: event.device_id,
                    table_id: event.table_id,
                    status: event.status,
                    since: event.since,
                    server_time: server_time.clone(),
                });
            }
            Ok((
                StatusCode::OK,
                Json(SyncAck {
                    device_id: auth.device_id,
                    acked_seq: seq,
                    server_time,
                    duplicates,
                }),
            ))
        }
    }
}

fn invalid(message: &str, request_id: &str) -> ApiError {
    ApiError::new(ErrorCode::InvalidPayload, message, request_id)
}

fn enforce_rate_limit(
    state: &AppState,
    device_id: &str,
    route: Route,
    request_id: &str,
) -> Result<(), ApiError> {
    match state.limiter.check(device_id, route) {
        LimitDecision::Allowed => Ok(()),
        LimitDecision::Limited { retry_after_s } => {
            Err(
                ApiError::new(ErrorCode::RateLimited, "rate limit exceeded", request_id)
                    .with_retry_after(retry_after_s),
            )
        }
    }
}
