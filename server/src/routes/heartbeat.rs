//! `POST /v1/heartbeat` (`docs/server-api.md` section 5).
//!
//! A heartbeat is metadata. The shared schema already rejects unknown fields,
//! so `image`, `frame`, `rtsp_url`, and `password` fail validation before they
//! reach the database. The payload is stored verbatim for diagnosis, but the
//! only fields this handler reads are the ones it needs to route the record.

use axum::body::Bytes;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::{Extension, Json};
use contracts::Contract;
use serde_json::{json, Value};

use crate::auth::DeviceAuth;
use crate::error::{ApiError, ErrorCode, RequestId};
use crate::model::{now_utc, WsMessage};
use crate::ratelimit::{LimitDecision, Route};
use crate::state::AppState;
use crate::store::StoreError;

/// Handle one heartbeat.
pub async fn heartbeat(
    State(state): State<AppState>,
    Extension(request_id): Extension<RequestId>,
    auth: DeviceAuth,
    body: Bytes,
) -> Result<impl IntoResponse, ApiError> {
    let request_id = request_id.0;
    match state.limiter.check(&auth.device_id, Route::Heartbeat) {
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
    contracts::validate(Contract::Heartbeat, &payload)
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

    let session_id = payload
        .get("session_id")
        .and_then(Value::as_str)
        .ok_or_else(|| invalid("missing session_id", &request_id))?;
    let sent_at = payload
        .get("sent_at")
        .and_then(Value::as_str)
        .ok_or_else(|| invalid("missing sent_at", &request_id))?;

    state
        .store
        .insert_heartbeat(&auth.device_id, session_id, sent_at, &payload)
        .await
        .map_err(|error: StoreError| ApiError::internal(&request_id, &error))?;

    let server_time = now_utc();
    state.publish(WsMessage::DeviceSeen {
        event_id: 0,
        device_id: auth.device_id.clone(),
        session_id: session_id.to_string(),
        server_time: server_time.clone(),
    });

    Ok((
        StatusCode::OK,
        Json(json!({
            "accepted": true,
            "server_time": server_time,
        })),
    ))
}

fn invalid(message: &str, request_id: &str) -> ApiError {
    ApiError::new(ErrorCode::InvalidPayload, message, request_id)
}
