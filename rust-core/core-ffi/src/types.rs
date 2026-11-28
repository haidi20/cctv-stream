//! Types that cross the UniFFI boundary.
//!
//! Durations are milliseconds (`u64`) instead of `std::time::Duration` so the
//! Kotlin binding is plain data with no hidden conversion. Timestamps are
//! monotonic milliseconds since the current engine start, not wall-clock time;
//! Kotlin maps them to `Instant`/`System.currentTimeMillis()` for display.

use spatial_engine::TableStatus as EngineTableStatus;

/// Stream connection state reported by [`crate::EngineHealth`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum StreamStatus {
    /// Not started, or stopped by the caller.
    Idle,
    /// Waiting for the first frame from the caller-owned source.
    Connecting,
    /// A frame arrived recently.
    Connected,
    /// The camera cannot be reached (wrong IP, different network, AP isolation).
    Unreachable,
    /// The TCP/RTSP handshake timed out, or frames stopped arriving.
    Timeout,
    /// The camera rejected the credentials.
    AuthFailed,
    /// The stream format is not supported.
    Protocol,
    /// The camera closed an established stream.
    Closed,
    /// Any other failure; details stay in native logs.
    Error,
}

impl StreamStatus {
    /// Stable lowercase form used by JSON and logs.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::Connecting => "connecting",
            Self::Connected => "connected",
            Self::Unreachable => "unreachable",
            Self::Timeout => "timeout",
            Self::AuthFailed => "auth_failed",
            Self::Protocol => "protocol",
            Self::Closed => "closed",
            Self::Error => "error",
        }
    }
}

impl std::fmt::Display for StreamStatus {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.as_str())
    }
}

/// Table state. `Unknown` must never be folded into `Available`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TableStatus {
    /// No person is assigned to the table.
    Available,
    /// At least one person is assigned to the table.
    Occupied,
    /// The stream is down, stale, or the model is not ready.
    Unknown,
}

impl From<EngineTableStatus> for TableStatus {
    fn from(status: EngineTableStatus) -> Self {
        match status {
            EngineTableStatus::Available => Self::Available,
            EngineTableStatus::Occupied => Self::Occupied,
            EngineTableStatus::Unknown => Self::Unknown,
        }
    }
}

impl TableStatus {
    /// Stable lowercase form used by JSON and logs.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Available => "available",
            Self::Occupied => "occupied",
            Self::Unknown => "unknown",
        }
    }
}

/// One status transition for one table.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TableEvent {
    /// Stable table identifier from the configuration.
    pub table_id: String,
    /// New status.
    pub status: TableStatus,
    /// Monotonic milliseconds since engine start when the transition became stable.
    pub since_ms: u64,
    /// Highest model confidence assigned to the table in that frame.
    pub confidence: f32,
    /// People assigned to the table in that frame.
    pub persons_in_region: u32,
}

/// Snapshot of one configured table plus its latest status.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TableSnapshot {
    /// Stable table identifier from the configuration.
    pub table_id: String,
    /// Human readable label; falls back to the id when none was configured.
    pub label: String,
    /// Latest debounced status owned by Rust.
    pub status: TableStatus,
    /// People assigned to this table in the last analysed frame.
    pub persons_in_region: u32,
    /// Highest model confidence assigned to the table in that frame.
    pub confidence: f32,
    /// Monotonic milliseconds since engine start when the status became stable.
    pub since_ms: u64,
}

/// One person box in normalised frame coordinates (`0.0..=1.0`).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DetectionBox {
    /// Left edge as a fraction of the frame width.
    pub x: f32,
    /// Top edge as a fraction of the frame height.
    pub y: f32,
    /// Box width as a fraction of the frame width.
    pub width: f32,
    /// Box height as a fraction of the frame height.
    pub height: f32,
    /// Model confidence for this person.
    pub confidence: f32,
}

/// Event delivered to every subscribed listener, in order.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum EngineEvent {
    /// A table changed state.
    Table {
        /// The transition.
        event: TableEvent,
    },
    /// The stream health changed.
    Stream {
        /// New state.
        status: StreamStatus,
    },
}

/// Snapshot of engine health for diagnostics and the UI badge.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct EngineHealth {
    /// Stable camera identifier of the session that produced this snapshot.
    ///
    /// Empty before the first successful `start` and after a failed start. The
    /// engine serves one camera at a time, so this is the only camera whose
    /// counters and table statuses are reported here.
    pub camera_id: String,
    /// Current stream state.
    pub stream: StreamStatus,
    /// Frames accepted since start.
    pub frames_in: u64,
    /// Frames sampled for inference since start.
    pub frames_sampled: u64,
    /// Successful stream connections since start.
    pub connects: u32,
    /// Failed connection attempts since the last success.
    pub reconnect_failures: u32,
    /// Age of the newest frame, or `0` before the first frame.
    pub last_frame_age_ms: u64,
    /// Configured tables.
    pub tables: u32,
    /// Tables currently `available`.
    pub tables_available: u32,
    /// Tables currently `occupied`.
    pub tables_occupied: u32,
    /// Tables currently `unknown`.
    pub tables_unknown: u32,
    /// Engine uptime since the current start.
    pub uptime_ms: u64,
    /// Inference rate the session requested, in frames per second.
    ///
    /// `0.0` before a session starts. The actual sampled rate can be lower
    /// when a frame takes longer than the interval; compare this with
    /// `frames_sampled`/`uptime_ms` to see the achievable rate.
    pub inference_fps: f64,
    /// Age of the last inference attempt, or `0` before the first one.
    pub last_inference_age_ms: u64,
    /// Delay from the newest frame arriving to the last inference attempt.
    ///
    /// `0` before any inference. This is the honest pipeline latency the UI
    /// shows beside the timestamp; a slow detector makes it grow while
    /// `last_frame_age_ms` alone only says how old the frame is.
    pub frame_to_inference_ms: u64,
    /// Resident set size of this process in KiB, read from `/proc/self/statm`.
    ///
    /// `0` when the platform does not expose it. This is the number a soak
    /// compares over time to tell a native leak from a Compose leak.
    pub rss_kb: u64,
    /// Frames that arrived while the detector was busy and were not sampled.
    ///
    /// A growing value at a fixed FPS means frames arrive faster than the
    /// detector can follow; it is drop-by-backpressure, never a queue.
    pub frames_dropped: u64,
    /// p50 of the recent inference window in milliseconds, or `0` before the
    /// first successful inference.
    pub inference_p50_ms: u64,
    /// p95 of the recent inference window in milliseconds, or `0` before the
    /// first successful inference.
    pub inference_p95_ms: u64,
    /// True when the live stream aspect ratio no longer matches the calibration.
    pub needs_recalibration: bool,
    /// True when the camera no longer looks at the scene the map was drawn for.
    ///
    /// A panned, tilted, or physically bumped camera keeps producing frames and
    /// plausible-looking detections, so nothing else in the engine notices. While
    /// this is true every table is reported `unknown`; asking the user to
    /// re-draw the map is the only honest fix.
    pub view_moved: bool,
    /// Fraction of the view grid that differs from the calibration frame,
    /// `0.0..=1.0`.
    ///
    /// `0.0` before the first compared frame. This is the number behind
    /// `view_moved`: a banner can say how far the view drifted instead of only
    /// that it changed, and a support report can tell "3% (lighting)" from
    /// "80% (camera knocked)".
    pub view_changed_fraction: f32,
    /// True when an ONNX model has been loaded.
    pub model_ready: bool,
}

/// Configuration for one engine start.
///
/// The engine does not open RTSP itself yet: the app decodes the preview and
/// supplies RGB frames through [`crate::Engine::push_frame`]. This keeps the
/// Rust side free of a hardware codec dependency while the status math and AI
/// inference stay authoritative in Rust.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct EngineConfig {
    /// Stable camera identifier this engine session belongs to.
    ///
    /// The FFI rejects blank values, values longer than 64 characters, and
    /// values containing anything other than `A-Z a-z 0-9 . _ -`. In particular
    /// a URL, `@`, `/`, or whitespace is rejected without echoing the input, so
    /// a credential cannot leak through the engine error.
    pub camera_id: String,
    /// Absolute path of the ONNX model (copied out of the APK cache first).
    pub model_path: String,
    /// Absolute path of `libonnxruntime.so`; required on Android.
    pub onnx_runtime_path: String,
    /// Table configuration JSON (`version: 2`, normalised coordinates).
    pub tables_json: String,
    /// Inference rate in frames per second; clamped to `0.5..=24`.
    pub inference_fps: f64,
    /// Debounce hold in milliseconds; clamped to `0..=60000`.
    pub debounce_ms: u64,
    /// ONNX Runtime intra-op threads for this session.
    ///
    /// `0` means "use the phone-friendly default"
    /// ([`ai_engine::detector::default_intra_threads`], capped at 4). A caller
    /// that wants to trade latency for CPU can pin a lower number; the value
    /// is clamped to `0..=4` so an exported config cannot oversubscribe a
    /// low-end phone.
    pub intra_threads: u32,
}
