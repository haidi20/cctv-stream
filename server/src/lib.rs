//! Server library: router, state, and the pieces handlers share.
//!
//! Integration tests build the same router `main` serves, so a test can never
//! pass against a configuration production does not use.

pub mod auth;
pub mod config;
pub mod error;
pub mod model;
pub mod ratelimit;
pub mod routes;
pub mod state;
pub mod store;
pub mod ws;

use axum::extract::DefaultBodyLimit;
use axum::http::{header, HeaderValue, Request};
use axum::middleware::{self, Next};
use axum::response::Response;
use axum::routing::{get, post};
use axum::Router;
use tower_http::trace::TraceLayer;

use crate::error::RequestId;
use crate::state::AppState;

/// Attach a request id and echo it on every response, including errors.
pub async fn request_id_layer(mut request: Request<axum::body::Body>, next: Next) -> Response {
    let existing = request
        .headers()
        .get("x-request-id")
        .and_then(|value| value.to_str().ok())
        .filter(|value| !value.is_empty() && value.len() <= 128)
        .map(|value| value.to_string());
    let request_id = existing.map(RequestId).unwrap_or_else(RequestId::generate);

    request.extensions_mut().insert(request_id.clone());
    let mut response = next.run(request).await;
    if let Ok(value) = HeaderValue::from_str(&request_id.0) {
        response.headers_mut().insert("x-request-id", value);
    }
    response
}

/// Build the application router.
pub fn build_app(state: AppState) -> Router {
    let max_body = state.config.max_body_bytes;

    Router::new()
        .route("/health", get(routes::health::health))
        .route("/v1/sync", post(routes::sync::sync))
        .route("/v1/heartbeat", post(routes::heartbeat::heartbeat))
        .route("/v1/snapshot", post(routes::snapshot::snapshot))
        .route("/v1/ws", get(ws::ws))
        .layer(DefaultBodyLimit::max(max_body))
        .layer(TraceLayer::new_for_http())
        .layer(middleware::from_fn(request_id_layer))
        .layer(tower_http::limit::RequestBodyLimitLayer::new(max_body))
        .with_state(state)
}

/// Header name used for request ids; exposed for tests.
pub const REQUEST_ID_HEADER: header::HeaderName = header::HeaderName::from_static("x-request-id");
