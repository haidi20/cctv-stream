//! `POST /v1/snapshot` (`docs/server-api.md` section 6).

use axum::body::Bytes;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::{Extension, Json};
use contracts::Contract;
use serde_json::Value;

use crate::auth::DeviceAuth;
use crate::error::{ApiError, ErrorCode, RequestId};
use crate::model::{now_utc, SnapshotAck, SnapshotAckTable};
use crate::ratelimit::{LimitDecision, Route};
use crate::state::AppState;
use crate::store::SnapshotTable;

/// Handle a reconciliation snapshot.
pub async fn snapshot(
    State(state): State<AppState>,
    Extension(request_id): Extension<RequestId>,
    auth: DeviceAuth,
    body: Bytes,
) -> Result<impl IntoResponse, ApiError> {
    let request_id = request_id.0;
    match state.limiter.check(&auth.device_id, Route::Snapshot) {
        LimitDecision::Allowed => {}
        LimitDecision::Limited { retry_after_s } => {
            return Err(
                ApiError::new(ErrorCode::RateLimited, "rate limit exceeded", request_id)
                    .with_retry_after(retry_after_s),
            );
        }
    }

    let payload: Value = serde_json::from_slice(&body)
        .map_err(|_| invalid("body is not valid JSON", &request_id))?;
    contracts::validate(Contract::SnapshotRequest, &payload)
        .map_err(|_| invalid("body does not match the contract", &request_id))?;

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

    let tables = payload
        .get("tables")
        .and_then(Value::as_array)
        .ok_or_else(|| invalid("missing tables", &request_id))?
        .iter()
        .map(|table| {
            let table_id = table
                .get("table_id")
                .and_then(Value::as_str)
                .ok_or_else(|| invalid("missing table_id", &request_id))?;
            let status = table
                .get("status")
                .and_then(Value::as_str)
                .ok_or_else(|| invalid("missing status", &request_id))?;
            let since = table
                .get("since")
                .and_then(Value::as_str)
                .ok_or_else(|| invalid("missing since", &request_id))?;
            Ok(SnapshotTable {
                table_id: table_id.to_string(),
                status: status.to_string(),
                since: since.to_string(),
            })
        })
        .collect::<Result<Vec<_>, ApiError>>()?;

    let conflicts = state
        .store
        .apply_snapshot(&auth.device_id, &tables)
        .await
        .map_err(|error| ApiError::internal(&request_id, &error))?;

    if !conflicts.is_empty() {
        tracing::warn!(
            request_id,
            device_id = %auth.device_id,
            conflicts = conflicts.len(),
            "snapshot disagreed with sequenced history; kept both for audit"
        );
    }

    let response = SnapshotAck {
        accepted: true,
        server_time: now_utc(),
        tables: tables
            .iter()
            .map(|table| SnapshotAckTable {
                table_id: table.table_id.clone(),
                status: table.status.clone(),
                source: "device".to_string(),
            })
            .collect(),
    };

    Ok((StatusCode::OK, Json(response)))
}

fn invalid(message: &str, request_id: &str) -> ApiError {
    ApiError::new(ErrorCode::InvalidPayload, message, request_id)
}
