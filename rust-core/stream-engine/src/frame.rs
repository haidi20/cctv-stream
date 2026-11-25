//! Frame container shared by sources and the AI engine.

use std::sync::Arc;
use std::time::Duration;

/// One decoded frame plus the metadata the rest of the pipeline needs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Frame {
    /// Monotonic sequence number, independent of media timestamps.
    pub seq: u64,
    /// Monotonic capture time on the local clock.
    pub captured_at: Duration,
    /// Frame width in pixels.
    pub width: u32,
    /// Frame height in pixels.
    pub height: u32,
    /// Pixel payload; empty for metadata-only tests.
    pub payload: Arc<[u8]>,
}

impl Frame {
    /// Metadata-only frame, useful for pacing tests.
    pub fn new(seq: u64, captured_at: Duration, width: u32, height: u32) -> Self {
        Self {
            seq,
            captured_at,
            width,
            height,
            payload: Arc::from(Vec::new()),
        }
    }

    /// Attach a pixel payload.
    pub fn with_payload(mut self, payload: impl Into<Arc<[u8]>>) -> Self {
        self.payload = payload.into();
        self
    }

    /// Number of payload bytes.
    pub fn payload_len(&self) -> usize {
        self.payload.len()
    }
}
