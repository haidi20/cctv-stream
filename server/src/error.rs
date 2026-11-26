//! Error envelope and request ids.
//!
//! Every response the API produces carries `X-Request-Id`, and every error body
//! uses the shape in `docs/server-api.md` section 9. Messages are fixed strings:
//! a payload, a database error, or a token must never reach the client.

use std::fmt;

use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::json;
use uuid::Uuid;

/// Error codes from `docs/server-api.md` section 9.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorCode {
    /// Schema or JSON invalid.
    InvalidPayload,
    /// Missing, malformed, or revoked token.
    Unauthorized,
    /// Token does not own the `device_id` in the body.
    Forbidden,
    /// Missing events; `expected_seq` tells the client where to resume.
    SeqGap,
    /// Batch or body above the configured limit.
    PayloadTooLarge,
    /// Per-device rate limit hit; `Retry-After` is set.
    RateLimited,
    /// Anything unexpected. Details stay in the server log.
    Internal,
}

impl ErrorCode {
    /// Stable wire string.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::InvalidPayload => "invalid_payload",
            Self::Unauthorized => "unauthorized",
            Self::Forbidden => "forbidden",
            Self::SeqGap => "seq_gap",
            Self::PayloadTooLarge => "payload_too_large",
            Self::RateLimited => "rate_limited",
            Self::Internal => "internal",
        }
    }

    /// HTTP status for this code.
    pub const fn status(self) -> StatusCode {
        match self {
            Self::InvalidPayload => StatusCode::BAD_REQUEST,
            Self::Unauthorized => StatusCode::UNAUTHORIZED,
            Self::Forbidden => StatusCode::FORBIDDEN,
            Self::SeqGap => StatusCode::CONFLICT,
            Self::PayloadTooLarge => StatusCode::PAYLOAD_TOO_LARGE,
            Self::RateLimited => StatusCode::TOO_MANY_REQUESTS,
            Self::Internal => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }
}

/// An API error that is safe to serialise.
#[derive(Debug)]
pub struct ApiError {
    code: ErrorCode,
    message: String,
    request_id: String,
    expected_seq: Option<i64>,
    retry_after_s: Option<u64>,
}

impl ApiError {
    /// Build an error with a fixed, client-safe message.
    pub fn new(code: ErrorCode, message: impl Into<String>, request_id: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            request_id: request_id.into(),
            expected_seq: None,
            retry_after_s: None,
        }
    }

    /// Add `expected_seq` to a `seq_gap` error.
    pub fn with_expected_seq(mut self, expected_seq: i64) -> Self {
        self.expected_seq = Some(expected_seq);
        self
    }

    /// Add a `Retry-After` header to a rate-limit error.
    pub fn with_retry_after(mut self, seconds: u64) -> Self {
        self.retry_after_s = Some(seconds);
        self
    }

    /// HTTP status derived from the code.
    pub const fn status(&self) -> StatusCode {
        self.code.status()
    }

    /// Stable code, used by tests and by `tower_http` traces.
    pub const fn code(&self) -> ErrorCode {
        self.code
    }

    /// Request id echoed to the client.
    pub fn request_id(&self) -> &str {
        &self.request_id
    }

    /// Internal error that logs its cause but never returns it.
    pub fn internal(request_id: &str, cause: &dyn fmt::Display) -> Self {
        tracing::error!(request_id, error = %cause, "internal server error");
        Self::new(ErrorCode::Internal, "internal error", request_id)
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let mut body = json!({
            "error": {
                "code": self.code.as_str(),
                "message": self.message,
                "request_id": self.request_id,
            }
        });
        if let Some(expected) = self.expected_seq {
            body["expected_seq"] = json!(expected);
        }

        let mut response = (self.code.status(), Json(body)).into_response();
        if let Ok(value) = HeaderValue::from_str(&self.request_id) {
            response.headers_mut().insert("x-request-id", value);
        }
        if let Some(seconds) = self.retry_after_s {
            if let Ok(value) = HeaderValue::from_str(&seconds.to_string()) {
                response.headers_mut().insert(header::RETRY_AFTER, value);
            }
        }
        response
    }
}

/// Request id carried by every request, injected by [`crate::request_id_layer`].
#[derive(Debug, Clone)]
pub struct RequestId(pub String);

impl RequestId {
    /// A fresh `req_` prefixed UUID.
    pub fn generate() -> Self {
        Self(format!("req_{}", Uuid::new_v4().simple()))
    }
}

impl Default for RequestId {
    fn default() -> Self {
        Self::generate()
    }
}

impl fmt::Display for RequestId {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}
