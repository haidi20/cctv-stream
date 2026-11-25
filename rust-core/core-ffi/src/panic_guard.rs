//! Last line of defence at the FFI boundary.
//!
//! The crate is compiled with the default unwind strategy, so a bug inside the
//! pipeline is turned into [`EngineError::Internal`] instead of crashing the
//! Android process. Panic payloads are not forwarded: they can contain
//! credentials or model paths.

use std::any::Any;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::error::EngineError;

/// Run `operation`, converting a panic into [`EngineError::Internal`].
pub(crate) fn guard<T>(
    operation: impl FnOnce() -> Result<T, EngineError>,
) -> Result<T, EngineError> {
    match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(result) => result,
        Err(payload) => {
            log_panic(payload.as_ref());
            Err(EngineError::Internal)
        }
    }
}

fn log_panic(payload: &(dyn Any + Send)) {
    let message = payload
        .downcast_ref::<&str>()
        .copied()
        .or_else(|| payload.downcast_ref::<String>().map(String::as_str))
        .unwrap_or("non-string panic payload");

    // The message is written by our own code, but redaction is cheap insurance
    // against a future `panic!("... {url}")` slipping through.
    eprintln!(
        "cctv-stream: panic caught at FFI boundary: {}",
        stream_engine::redact_endpoint(message)
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn passes_ok_values_through() {
        assert_eq!(guard(|| Ok(7_u32)), Ok(7));
    }

    #[test]
    fn passes_errors_through() {
        assert_eq!(
            guard::<()>(|| Err(EngineError::NotRunning)),
            Err(EngineError::NotRunning)
        );
    }

    #[test]
    fn converts_a_panic_into_internal_error() {
        let result = guard::<()>(|| panic!("boom"));
        assert_eq!(result, Err(EngineError::Internal));
    }
}
