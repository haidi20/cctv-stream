//! Device enrollment and Bearer authentication.
//!
//! Tokens are 256-bit random values prefixed with `ctv_`. Only `sha256(token)`
//! is stored, so a database leak does not hand out working credentials. The
//! plaintext is printed once by the `enroll` subcommand and never written to a
//! log, a response body, or the database.

use axum::extract::FromRequestParts;
use axum::http::header;
use axum::http::request::Parts;
use sha2::{Digest, Sha256};

use crate::error::{ApiError, ErrorCode, RequestId};
use crate::state::AppState;

/// Token prefix, used to reject obviously wrong header values early.
pub const TOKEN_PREFIX: &str = "ctv_";

/// Generate a fresh device token.
///
/// # Errors
///
/// Returns an error when the operating system random source fails.
pub fn generate_token() -> Result<String, getrandom::Error> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes)?;
    let mut token = String::with_capacity(TOKEN_PREFIX.len() + 64);
    token.push_str(TOKEN_PREFIX);
    for byte in bytes {
        use std::fmt::Write as _;
        let _ = write!(token, "{byte:02x}");
    }
    Ok(token)
}

/// Hash a token for storage and lookup.
pub fn hash_token(token: &str) -> String {
    let digest = Sha256::digest(token.as_bytes());
    let mut hex = String::with_capacity(64);
    for byte in digest {
        use std::fmt::Write as _;
        let _ = write!(hex, "{byte:02x}");
    }
    hex
}

/// The device that owns the presented token.
#[derive(Debug, Clone)]
pub struct DeviceAuth {
    /// Device id bound to the token.
    pub device_id: String,
}

impl FromRequestParts<AppState> for DeviceAuth {
    type Rejection = ApiError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let request_id = parts
            .extensions
            .get::<RequestId>()
            .cloned()
            .unwrap_or_default();

        let header_value = parts
            .headers
            .get(header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .ok_or_else(|| {
                ApiError::new(
                    ErrorCode::Unauthorized,
                    "missing bearer token",
                    request_id.0.clone(),
                )
            })?;

        let token = header_value
            .strip_prefix("Bearer ")
            .map(str::trim)
            .filter(|token| token.starts_with(TOKEN_PREFIX))
            .ok_or_else(|| {
                ApiError::new(
                    ErrorCode::Unauthorized,
                    "malformed bearer token",
                    request_id.0.clone(),
                )
            })?;

        let hash = hash_token(token);
        match state.store.device_for_token(&hash).await {
            Ok(Some(device_id)) => Ok(Self { device_id }),
            Ok(None) => Err(ApiError::new(
                ErrorCode::Unauthorized,
                "unknown or revoked token",
                request_id.0,
            )),
            Err(error) => Err(ApiError::internal(&request_id.0, &error)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_are_prefixed_and_unique() {
        let first = generate_token().expect("random source");
        let second = generate_token().expect("random source");
        assert!(first.starts_with(TOKEN_PREFIX));
        assert_eq!(first.len(), TOKEN_PREFIX.len() + 64);
        assert_ne!(first, second);
    }

    #[test]
    fn hashing_is_stable_and_hides_the_token() {
        let token = "ctv_0123456789abcdef";
        let hash = hash_token(token);
        assert_eq!(hash, hash_token(token));
        assert_eq!(hash.len(), 64);
        assert!(!hash.contains(token));
    }
}
