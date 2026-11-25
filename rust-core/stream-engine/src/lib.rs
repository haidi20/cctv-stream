//! Stream ingestion for the CCTV occupancy pipeline.
//!
//! Two sources share the same downstream contract: a live RTSP camera and a
//! local video file used for tests and demos. Everything here is pure Rust and
//! testable on a host without a camera, a decoder, or Android.

pub mod error;
pub mod file_source;
pub mod frame;
pub mod frame_sampler;
pub mod metrics;
pub mod reconnect;
pub mod ring_buffer;
#[cfg(feature = "rtsp")]
pub mod rtsp_client;

pub use error::{classify_io, redact_endpoint, StreamError, StreamErrorKind};
pub use file_source::{
    default_reconnect, DecodedFrame, FileSource, FileSourceConfig, FileSourceError, FrameDecoder,
};
pub use frame::Frame;
pub use frame_sampler::{FrameSampler, SamplerError, MAX_RECOMMENDED_FPS, MIN_RECOMMENDED_FPS};
pub use metrics::StreamMetrics;
pub use reconnect::{ReconnectError, ReconnectPolicy, DEFAULT_BACKOFF_SCHEDULE_MS};
pub use ring_buffer::RingBuffer;
#[cfg(feature = "rtsp")]
pub use rtsp_client::{
    map_error as map_rtsp_error, AccessUnit, RtspConfig, RtspSource, RtspTransport,
    DEFAULT_CONNECT_TIMEOUT,
};

/// Where frames come from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Source {
    /// Live camera over RTSP.
    Rtsp {
        /// Full RTSP URL. Never logged or serialised without redaction.
        url: String,
    },
    /// Local video file used for tests and demos.
    File(FileSourceConfig),
}

impl Source {
    /// Redacted description that is safe to log.
    pub fn describe(&self) -> String {
        match self {
            Self::Rtsp { url } => format!("rtsp:{}", redact_endpoint(url)),
            Self::File(config) => format!("file:{}", config.path),
        }
    }

    /// True when the source can be replayed after EOF.
    pub fn is_replayable(&self) -> bool {
        match self {
            Self::Rtsp { .. } => false,
            Self::File(config) => config.loop_playback,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rtsp_description_is_redacted() {
        let source = Source::Rtsp {
            url: "rtsp://admin:secret@10.0.0.2:554/stream1?token=abc".to_string(),
        };
        let described = source.describe();
        assert!(!described.contains("secret"));
        assert!(!described.contains("admin"));
        assert!(!described.contains("token"));
        assert!(described.contains("10.0.0.2:554"));
    }

    #[test]
    fn only_looping_files_are_replayable() {
        let looping = Source::File(FileSourceConfig {
            path: "a.mp4".to_string(),
            fps: 25,
            loop_playback: true,
        });
        let once = Source::File(FileSourceConfig {
            path: "a.mp4".to_string(),
            fps: 25,
            loop_playback: false,
        });
        let camera = Source::Rtsp {
            url: "rtsp://10.0.0.2/live".to_string(),
        };

        assert!(looping.is_replayable());
        assert!(!once.is_replayable());
        assert!(!camera.is_replayable());
    }
}
