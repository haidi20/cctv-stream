//! Live RTSP ingestion on top of [`retina`].
//!
//! The module is behind the `rtsp` feature so the rest of the workspace keeps
//! building offline. It exposes one blocking pull API, [`RtspSource`], which
//! hands out encoded access units in arrival order. Decoding stays in the AI
//! engine: this layer never allocates a pixel buffer, otherwise the frame path
//! would depend on a specific codec implementation.

use std::time::Duration;

use futures::StreamExt;
use retina::client::{
    Credentials, Demuxed, PlayOptions, Session, SessionOptions, SetupOptions, StreamDirection,
    Transport,
};
use retina::codec::{CodecItem, FrameFormat};
use url::Url;

use crate::error::{redact_endpoint, StreamError};

/// How long a single `DESCRIBE` may take before it is reported as a timeout.
pub const DEFAULT_CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

/// Transport preference for the RTP media channel.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum RtspTransport {
    /// RTP interleaved inside the RTSP TCP connection.
    ///
    /// Works across NAT and AP isolation, and it is what most consumer cameras
    /// accept. This is the default.
    #[default]
    Tcp,
    /// RTP over UDP. Lower latency on a healthy LAN, but drops on loss.
    Udp,
}

impl RtspTransport {
    fn to_retina(self) -> Transport {
        match self {
            Self::Tcp => Transport::Tcp(Default::default()),
            Self::Udp => Transport::Udp(Default::default()),
        }
    }
}

/// Connection settings for one camera.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RtspConfig {
    /// Full camera URL, including credentials when the camera needs them.
    ///
    /// The value is never logged; [`RtspConfig::describe`] returns a redacted
    /// form.
    pub url: String,
    /// Transport for the media channel.
    pub transport: RtspTransport,
    /// Upper bound for the connect + DESCRIBE handshake.
    pub connect_timeout: Duration,
}

impl RtspConfig {
    /// Build a config with the documented defaults.
    pub fn new(url: impl Into<String>) -> Self {
        Self {
            url: url.into(),
            transport: RtspTransport::default(),
            connect_timeout: DEFAULT_CONNECT_TIMEOUT,
        }
    }

    /// Redacted description that is safe to log.
    pub fn describe(&self) -> String {
        redact_endpoint(&self.url)
    }
}

/// One encoded video access unit from the camera.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AccessUnit {
    /// Raw Annex B bytes (start codes included).
    pub data: Vec<u8>,
    /// Normal play time since the start of the stream.
    pub npt: Duration,
    /// True when decoding may start here (IDR / CRA / BLA).
    pub keyframe: bool,
    /// RTP packets lost immediately before this access unit.
    pub loss: u16,
}

/// Live RTSP source.
///
/// The session runs on a Tokio runtime owned by this struct, so callers do not
/// need an async executor. [`RtspSource::next_unit`] blocks until the next
/// access unit arrives and must not be called from inside an async task.
pub struct RtspSource {
    runtime: tokio::runtime::Runtime,
    /// Taken in `Drop` so the demuxer is torn down while the runtime is still
    /// entered: `retina` starts a `TEARDOWN` task with `tokio::spawn` from its
    /// `Drop` impl, which panics outside a runtime.
    demuxed: Option<Demuxed>,
    describe: String,
}

impl RtspSource {
    /// Connect, negotiate a video stream, and start playing.
    pub fn connect(config: RtspConfig) -> Result<Self, StreamError> {
        let parsed = parse_url(&config)?;
        let credentials = credentials_from_url(&config.url);
        let describe = config.describe();

        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|error| StreamError::Io { kind: error.kind() })?;

        let mut options = SessionOptions::default().creds(credentials);
        options = options.user_agent(user_agent());

        let demuxed = runtime.block_on(connect_and_play(&config, parsed, options))?;

        Ok(Self {
            runtime,
            demuxed: Some(demuxed),
            describe,
        })
    }

    /// Redacted endpoint, safe for logs and UI.
    pub fn describe(&self) -> &str {
        &self.describe
    }

    /// Block until the next video access unit arrives.
    ///
    /// Returns `Ok(None)` when the camera ended the presentation. Audio and
    /// metadata streams are skipped: the occupancy pipeline only needs video.
    pub fn next_unit(&mut self, timeout: Duration) -> Result<Option<AccessUnit>, StreamError> {
        let Self {
            runtime, demuxed, ..
        } = self;
        let demuxed = demuxed.as_mut().ok_or(StreamError::Closed)?;

        runtime.block_on(async {
            loop {
                let item = match tokio::time::timeout(timeout, demuxed.next()).await {
                    Ok(item) => item,
                    Err(_) => return Err(StreamError::Timeout),
                };

                match item {
                    None => return Ok(None),
                    Some(Err(error)) => return Err(map_error(&error)),
                    Some(Ok(CodecItem::VideoFrame(frame))) => {
                        return Ok(Some(AccessUnit {
                            data: frame.data().to_vec(),
                            npt: seconds_to_duration(frame.timestamp().elapsed_secs()),
                            keyframe: frame.is_random_access_point(),
                            loss: frame.loss(),
                        }));
                    }
                    Some(Ok(_)) => continue,
                }
            }
        })
    }
}

impl Drop for RtspSource {
    fn drop(&mut self) {
        let Self {
            runtime, demuxed, ..
        } = self;
        let Some(demuxed) = demuxed.take() else {
            return;
        };

        // Best effort: dropping the demuxer queues the camera `TEARDOWN`, and
        // yielding lets that task reach the socket before the runtime goes away.
        runtime.block_on(async move {
            drop(demuxed);
            for _ in 0..64 {
                tokio::task::yield_now().await;
            }
        });
    }
}

async fn connect_and_play(
    config: &RtspConfig,
    parsed: Url,
    options: SessionOptions,
) -> Result<Demuxed, StreamError> {
    let described =
        tokio::time::timeout(config.connect_timeout, Session::describe(parsed, options))
            .await
            .map_err(|_| StreamError::Timeout)??;

    let video = described
        .streams()
        .iter()
        .position(|stream| {
            stream.media() == "video" && stream.direction() == StreamDirection::Receiving
        })
        .ok_or_else(|| StreamError::protocol("camera offered no receiving video stream"))?;

    let mut session = described;
    session
        .setup(
            video,
            SetupOptions::default()
                .transport(config.transport.to_retina())
                .frame_format(FrameFormat::SIMPLE),
        )
        .await?;

    Ok(session.play(PlayOptions::default()).await?.demuxed()?)
}

impl std::fmt::Debug for RtspSource {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("RtspSource")
            .field("endpoint", &self.describe)
            .finish_non_exhaustive()
    }
}

impl From<retina::Error> for StreamError {
    fn from(error: retina::Error) -> Self {
        map_error(&error)
    }
}

fn user_agent() -> String {
    concat!("cctv-stream/", env!("CARGO_PKG_VERSION")).to_string()
}

fn parse_url(config: &RtspConfig) -> Result<Url, StreamError> {
    if config.url.trim().is_empty() {
        return Err(StreamError::protocol("rtsp url is empty"));
    }
    if config.connect_timeout.is_zero() {
        return Err(StreamError::protocol("connect timeout must be positive"));
    }

    let without_credentials = strip_credentials(&config.url);
    let parsed = Url::parse(&without_credentials)
        .map_err(|_| StreamError::protocol("rtsp url is not a valid URL"))?;

    if parsed.scheme() != "rtsp" {
        return Err(StreamError::protocol("only rtsp:// urls are supported"));
    }
    if parsed.host_str().is_none() {
        return Err(StreamError::protocol("rtsp url has no host"));
    }

    Ok(parsed)
}

/// `retina` refuses credentials inside the URL, so they are moved out of the
/// URL and into [`Credentials`].
fn strip_credentials(url: &str) -> String {
    let Some(scheme_end) = url.find("://") else {
        return url.to_string();
    };
    let authority_start = scheme_end + 3;
    let authority_end = url[authority_start..]
        .find('/')
        .map_or(url.len(), |index| authority_start + index);

    match url[authority_start..authority_end].rfind('@') {
        Some(index) => format!(
            "{}{}",
            &url[..authority_start],
            &url[authority_start + index + 1..]
        ),
        None => url.to_string(),
    }
}

fn credentials_from_url(url: &str) -> Option<Credentials> {
    let scheme_end = url.find("://")?;
    let authority_start = scheme_end + 3;
    let authority_end = url[authority_start..]
        .find('/')
        .map_or(url.len(), |index| authority_start + index);

    let userinfo = &url[authority_start..authority_end];
    let (userinfo, _) = userinfo.split_once('@')?;
    let (username, password) = userinfo.split_once(':').unwrap_or((userinfo, ""));

    if username.is_empty() {
        return None;
    }

    Some(Credentials {
        username: username.to_string(),
        password: password.to_string(),
    })
}

fn seconds_to_duration(seconds: f64) -> Duration {
    if !seconds.is_finite() || seconds <= 0.0 {
        return Duration::ZERO;
    }
    Duration::try_from_secs_f64(seconds).unwrap_or(Duration::MAX)
}

/// Map a `retina` error onto the coarse categories the UI understands.
///
/// `retina` renders full connection context (including the peer address) in
/// its `Display`, so the message is rebuilt from the status code and the I/O
/// kind instead of being forwarded.
pub fn map_error(error: &retina::Error) -> StreamError {
    match error.status_code() {
        Some(401) | Some(403) => StreamError::AuthFailed,
        Some(404) => StreamError::Unreachable,
        Some(status) if (500..600).contains(&status) => {
            StreamError::protocol(format!("camera rejected the request with {status}"))
        }
        Some(status) => StreamError::protocol(format!("unexpected RTSP status {status}")),
        None => classify_message(&error.to_string()),
    }
}

fn classify_message(message: &str) -> StreamError {
    if message.contains("Timeout") {
        return StreamError::Timeout;
    }
    if message.starts_with("Unable to connect to RTSP server") {
        return StreamError::Unreachable;
    }
    if message.contains("Error reading from RTSP peer") || message.contains("Error writing") {
        return StreamError::Closed;
    }
    StreamError::protocol("rtsp session failed")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::StreamErrorKind;

    fn config(url: &str) -> RtspConfig {
        RtspConfig::new(url)
    }

    #[test]
    fn describe_drops_credentials_and_query() {
        let config = config("rtsp://admin:hunter2@10.0.0.2:554/stream?token=abc");
        let described = config.describe();
        assert!(!described.contains("hunter2"));
        assert!(!described.contains("admin"));
        assert!(!described.contains("token"));
        assert!(described.contains("10.0.0.2:554"));
    }

    #[test]
    fn rejects_non_rtsp_and_missing_hosts() {
        for url in ["http://10.0.0.2/stream", "rtsp:///stream", "   "] {
            let error = parse_url(&config(url)).unwrap_err();
            assert_eq!(
                error.kind(),
                StreamErrorKind::Protocol,
                "unexpected kind for {url:?}"
            );
        }
    }

    #[test]
    fn rejects_zero_connect_timeout() {
        let mut config = config("rtsp://10.0.0.2/stream");
        config.connect_timeout = Duration::ZERO;
        assert!(parse_url(&config).is_err());
    }

    #[test]
    fn moves_credentials_out_of_the_url() {
        assert_eq!(
            strip_credentials("rtsp://admin:secret@10.0.0.2:554/ch1"),
            "rtsp://10.0.0.2:554/ch1"
        );
        assert_eq!(
            strip_credentials("rtsp://10.0.0.2:554/ch1"),
            "rtsp://10.0.0.2:554/ch1"
        );

        let credentials = credentials_from_url("rtsp://admin:secret@10.0.0.2/ch1").unwrap();
        assert_eq!(credentials.username, "admin");
        assert_eq!(credentials.password, "secret");
        assert!(credentials_from_url("rtsp://10.0.0.2/ch1").is_none());
    }

    #[test]
    fn npt_conversion_never_panics_on_hostile_timestamps() {
        assert_eq!(seconds_to_duration(f64::NAN), Duration::ZERO);
        assert_eq!(seconds_to_duration(-1.0), Duration::ZERO);
        assert_eq!(seconds_to_duration(1.5), Duration::from_millis(1_500));
    }

    #[test]
    fn unparseable_url_never_echoes_credentials() {
        let error = parse_url(&config("rtsp://admin:secret@")).unwrap_err();
        let rendered = error.to_string();
        assert!(!rendered.contains("secret"));
        assert!(!rendered.contains("admin"));
    }

    #[test]
    fn error_messages_are_coarse_and_endpoint_free() {
        let detail = classify_message("Unable to connect to RTSP server: refused at 10.0.0.2:554");
        assert_eq!(detail.kind(), StreamErrorKind::Unreachable);
        let rendered = detail.to_string();
        assert!(!rendered.contains("10.0.0.2"));

        assert_eq!(classify_message("Timeout").kind(), StreamErrorKind::Timeout);
        assert_eq!(
            classify_message("Error reading from RTSP peer: reset").kind(),
            StreamErrorKind::Closed
        );
    }
}
