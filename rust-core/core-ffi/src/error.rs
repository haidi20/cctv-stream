//! Error type returned to Kotlin.
//!
//! Every variant is safe to log: URLs, credentials, and filesystem paths are
//! never included. Use [`EngineError::user_message`] for UI copy.

use thiserror::Error;

use crate::types::StreamStatus;

/// Failure returned across the FFI boundary.
#[derive(Debug, Clone, PartialEq, Eq, Error, uniffi::Error)]
pub enum EngineError {
    /// `start` was called while an engine is already running.
    #[error("engine is already running")]
    AlreadyRunning,
    /// `start` was never called, or the engine was stopped.
    #[error("engine is not running")]
    NotRunning,
    /// The engine configuration was rejected.
    #[error("invalid configuration: {reason}")]
    Config {
        /// Short, endpoint-free reason.
        reason: String,
    },
    /// The table JSON failed validation.
    #[error("invalid table configuration: {reason}")]
    Tables {
        /// Short, endpoint-free reason.
        reason: String,
    },
    /// The camera could not be reached or dropped.
    #[error("stream error: {status}")]
    Stream {
        /// Classified stream state.
        status: StreamStatus,
    },
    /// ONNX Runtime or the model could not be used.
    #[error("model error: {reason}")]
    Model {
        /// Short reason without a full filesystem path.
        reason: String,
    },
    /// A frame handed to the engine was malformed.
    #[error("invalid frame: {reason}")]
    Frame {
        /// Short, endpoint-free reason.
        reason: String,
    },
    /// A worker did not finish within the shutdown deadline.
    #[error("engine did not stop within {timeout_ms} ms")]
    StopTimeout {
        /// The deadline that expired.
        timeout_ms: u64,
    },
    /// A native panic was caught at the FFI boundary.
    #[error("internal engine error")]
    Internal,
}

impl EngineError {
    /// Message that is safe to show in the app.
    pub fn user_message(&self) -> &'static str {
        match self {
            Self::AlreadyRunning => "Engine sudah berjalan",
            Self::NotRunning => "Engine belum berjalan",
            Self::Config { .. } => "Konfigurasi engine tidak valid",
            Self::Tables { .. } => "Konfigurasi meja tidak valid",
            Self::Stream { .. } => "Kamera tidak dapat dibaca",
            Self::Model { .. } => "Model deteksi tidak tersedia",
            Self::Frame { .. } => "Frame tidak valid",
            Self::StopTimeout { .. } => "Engine tidak berhenti tepat waktu",
            Self::Internal => "Terjadi kesalahan internal",
        }
    }
}

impl From<stream_engine::StreamError> for EngineError {
    fn from(error: stream_engine::StreamError) -> Self {
        use stream_engine::StreamErrorKind;

        let status = match error.kind() {
            StreamErrorKind::Unreachable => StreamStatus::Unreachable,
            StreamErrorKind::Timeout => StreamStatus::Timeout,
            StreamErrorKind::AuthFailed => StreamStatus::AuthFailed,
            StreamErrorKind::Protocol => StreamStatus::Protocol,
            StreamErrorKind::Closed => StreamStatus::Closed,
            StreamErrorKind::Decode | StreamErrorKind::Io => StreamStatus::Error,
        };
        Self::Stream { status }
    }
}

impl From<spatial_engine::ConfigError> for EngineError {
    fn from(error: spatial_engine::ConfigError) -> Self {
        Self::Tables {
            reason: error.to_string(),
        }
    }
}

impl From<ai_engine::AiError> for EngineError {
    fn from(error: ai_engine::AiError) -> Self {
        use ai_engine::AiError;

        // Capture the safe message before moving `error` into the match. Some
        // `ort` errors embed the model path, so raw details never cross FFI.
        let reason = error.user_message().to_string();
        match error {
            AiError::Config { .. } => Self::Config { reason },
            AiError::Model { .. } => Self::Model { reason },
            AiError::InvalidFrame { .. } => Self::Frame { reason },
            AiError::Inference { .. } | AiError::Output { .. } => Self::Model { reason },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stream_errors_are_classified_without_endpoints() {
        let error = EngineError::from(stream_engine::StreamError::AuthFailed);
        assert_eq!(
            error,
            EngineError::Stream {
                status: StreamStatus::AuthFailed
            }
        );
        assert!(!error.to_string().contains("rtsp://"));
    }

    #[test]
    fn user_messages_are_stable() {
        assert_eq!(
            EngineError::AlreadyRunning.user_message(),
            "Engine sudah berjalan"
        );
    }
}
