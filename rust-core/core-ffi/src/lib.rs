//! UniFFI bridge between the Kotlin app and the Rust occupancy engine.
//!
//! The bridge exposes a small, synchronous API. Expensive work (ONNX
//! inference) runs on the thread that calls [`Engine::push_frame`], so Kotlin
//! must invoke it from a background dispatcher. The FFI boundary never lets a
//! panic escape and never forwards raw endpoints or model paths.

mod api;
mod error;
mod panic_guard;
mod runtime;
mod state;
mod types;

pub use api::{frame_stale_after_ms, Engine, EngineListener};
pub use error::EngineError;
pub use types::{
    DetectionBox, EngineConfig, EngineEvent, EngineHealth, StreamStatus, TableEvent, TableSnapshot,
    TableStatus,
};

uniffi::setup_scaffolding!();
