//! Process-wide ONNX Runtime environment.
//!
//! ONNX Runtime allows exactly one environment per process. Creating a new
//! environment for every [`crate::Detector`] makes a stop/start cycle fail with
//! "only one environment is allowed per process", so the environment is built
//! once and shared.
//!
//! Android builds load `libonnxruntime.so` at runtime. The library shipped in
//! the official Maven AAR covers `arm64-v8a`, `armeabi-v7a`, `x86`, and
//! `x86_64`; the caller must pass its absolute path with
//! [`configure_library_path`] before the first detector is created.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use ort::environment::Environment;

use crate::error::AiError;

static LIBRARY_PATH: OnceLock<PathBuf> = OnceLock::new();
static ENVIRONMENT: OnceLock<Result<Environment, String>> = OnceLock::new();

/// Remember where `libonnxruntime.so` lives on Android.
///
/// Safe to call more than once with the same path. Calling it with a different
/// path after the first call is rejected, because ONNX Runtime can only be
/// loaded once per process.
///
/// On non-Android hosts the path is recorded but unused: `ort` links (or
/// downloads) the host build itself.
pub fn configure_library_path(path: impl AsRef<Path>) -> Result<(), AiError> {
    let path = path.as_ref();
    if path.as_os_str().is_empty() {
        return Err(AiError::Config {
            reason: "ONNX Runtime library path must not be empty".to_string(),
        });
    }
    if !path.is_file() {
        return Err(AiError::Model {
            reason: "ONNX Runtime library not found at the configured path".to_string(),
        });
    }

    match LIBRARY_PATH.set(path.to_path_buf()) {
        Ok(()) => Ok(()),
        Err(existing) if existing == path => Ok(()),
        Err(_) => Err(AiError::Config {
            reason: "ONNX Runtime library path is already configured".to_string(),
        }),
    }
}

/// Shared environment, created on first use.
pub(crate) fn environment() -> Result<Environment, AiError> {
    ENVIRONMENT
        .get_or_init(build)
        .clone()
        .map_err(|reason| AiError::Model { reason })
}

fn build() -> Result<Environment, String> {
    #[cfg(target_os = "android")]
    {
        let path = LIBRARY_PATH.get().ok_or_else(|| {
            "ONNX Runtime library path is not configured; call configureOrtLibrary() first"
                .to_string()
        })?;
        let builder = ort::init_from(path).map_err(|error| error.to_string())?;
        return builder
            .with_name("cctv-stream-ai")
            .build()
            .map_err(|error| error.to_string());
    }

    #[cfg(not(target_os = "android"))]
    {
        ort::init()
            .with_name("cctv-stream-ai")
            .build()
            .map_err(|error| error.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_an_empty_library_path() {
        assert!(matches!(
            configure_library_path(Path::new("")),
            Err(AiError::Config { .. })
        ));
    }

    #[test]
    fn rejects_a_missing_library_path() {
        let missing = std::env::temp_dir().join("cctv-stream-no-such-onnxruntime.so");
        assert!(matches!(
            configure_library_path(missing),
            Err(AiError::Model { .. })
        ));
    }
}
