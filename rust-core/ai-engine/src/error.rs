//! Errors raised by preprocessing, inference, and decoding.

use thiserror::Error;

/// Failure while preparing a frame or decoding model output.
#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum AiError {
    /// Frame dimensions or payload do not match each other.
    #[error("invalid frame: {reason}")]
    InvalidFrame {
        /// Human readable reason.
        reason: String,
    },
    /// Model file could not be loaded or the session failed.
    #[error("model unavailable: {reason}")]
    Model {
        /// Human readable reason, never a filesystem path with credentials.
        reason: String,
    },
    /// Inference failed.
    #[error("inference failed: {reason}")]
    Inference {
        /// Human readable reason.
        reason: String,
    },
    /// Model output did not match the expected YOLO layout.
    #[error("unexpected output: {reason}")]
    Output {
        /// Human readable reason.
        reason: String,
    },
    /// Configuration value is outside its allowed range.
    #[error("invalid config: {reason}")]
    Config {
        /// Human readable reason.
        reason: String,
    },
}

impl AiError {
    /// Message that is safe to show to a user.
    pub fn user_message(&self) -> &'static str {
        match self {
            Self::InvalidFrame { .. } => "Frame tidak valid",
            Self::Model { .. } => "Model deteksi tidak tersedia",
            Self::Inference { .. } => "Deteksi gagal dijalankan",
            Self::Output { .. } => "Keluaran model tidak dikenali",
            Self::Config { .. } => "Konfigurasi deteksi tidak valid",
        }
    }
}
