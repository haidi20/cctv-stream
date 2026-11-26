//! `GET /health` (`docs/server-api.md` section 8).
//!
//! Unauthenticated on purpose: uptime monitors and Caddy need it. It returns an
//! aggregate only, never a path, credential, or database detail.

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;

use crate::model::HealthResponse;
use crate::state::AppState;

/// Report process and database health.
pub async fn health(State(state): State<AppState>) -> impl IntoResponse {
    let database_ok = state.store.ping().await.is_ok();
    let uptime_s = state.started.elapsed().as_secs();
    let response = HealthResponse {
        status: if database_ok { "ok" } else { "degraded" },
        version: state.version,
        database: if database_ok { "ok" } else { "error" },
        uptime_s,
    };
    let code = if database_ok {
        StatusCode::OK
    } else {
        StatusCode::SERVICE_UNAVAILABLE
    };
    (code, Json(response))
}
