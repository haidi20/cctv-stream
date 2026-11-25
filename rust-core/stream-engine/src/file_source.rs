//! Local video file source with frame pacing.
//!
//! Decoding is delegated to a [`FrameDecoder`] implementation so that pacing,
//! loop handling, and timestamp normalisation can be tested without ffmpeg.

use std::time::Duration;

use thiserror::Error;

use crate::error::StreamError;
use crate::frame::Frame;
use crate::reconnect::{ReconnectError, ReconnectPolicy};

/// File source configuration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileSourceConfig {
    /// Absolute or relative path to the video container.
    pub path: String,
    /// Playback rate used for pacing; must match the container FPS.
    pub fps: u32,
    /// Restart from the first frame at EOF.
    pub loop_playback: bool,
}

impl FileSourceConfig {
    /// Frame interval implied by `fps`.
    pub fn frame_interval(&self) -> Duration {
        Duration::from_secs_f64(1.0 / f64::from(self.fps.max(1)))
    }
}

/// Configuration error for a file source.
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum FileSourceError {
    /// FPS zero cannot produce a pacing interval.
    #[error("fps must be greater than zero")]
    ZeroFps,
    /// The path is empty.
    #[error("path must not be empty")]
    EmptyPath,
    /// The decoder reported a problem after construction.
    #[error("decode failed: {0}")]
    Decode(String),
}

/// One decoded sample before pacing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedFrame {
    /// Media timestamp from the container; may not start at zero.
    pub pts: Duration,
    /// Frame width in pixels.
    pub width: u32,
    /// Frame height in pixels.
    pub height: u32,
}

/// Decoder abstraction so tests do not need ffmpeg.
pub trait FrameDecoder {
    /// Return the next frame, or `None` at EOF.
    fn next_frame(&mut self) -> Result<Option<DecodedFrame>, StreamError>;

    /// Seek back to the first keyframe.
    fn rewind(&mut self) -> Result<(), StreamError>;
}

/// File source that paces decoded frames and normalises the first timestamp.
#[derive(Debug)]
pub struct FileSource<D> {
    config: FileSourceConfig,
    decoder: D,
    interval: Duration,
    pass_first_pts: Option<Duration>,
    last_pts: Option<Duration>,
    loop_offset: Duration,
    next_deadline: Option<Duration>,
    seq: u64,
    pending: Option<Frame>,
    loops: u64,
    rejected: u64,
    finished: bool,
}

impl<D: FrameDecoder> FileSource<D> {
    /// Wrap a decoder with pacing and loop handling.
    pub fn new(config: FileSourceConfig, decoder: D) -> Result<Self, FileSourceError> {
        if config.fps == 0 {
            return Err(FileSourceError::ZeroFps);
        }
        if config.path.trim().is_empty() {
            return Err(FileSourceError::EmptyPath);
        }

        let interval = config.frame_interval();
        Ok(Self {
            config,
            decoder,
            interval,
            pass_first_pts: None,
            last_pts: None,
            loop_offset: Duration::ZERO,
            next_deadline: None,
            seq: 0,
            pending: None,
            loops: 0,
            rejected: 0,
            finished: false,
        })
    }

    /// Effective configuration.
    pub fn config(&self) -> &FileSourceConfig {
        &self.config
    }

    /// Frame interval used for pacing.
    pub fn interval(&self) -> Duration {
        self.interval
    }

    /// Number of times the file restarted from the beginning.
    pub fn loops(&self) -> u64 {
        self.loops
    }

    /// Frames rejected because their PTS went backwards.
    pub fn rejected(&self) -> u64 {
        self.rejected
    }

    /// Pull one frame, pacing it against `now`.
    ///
    /// Returns `Ok(None)` when the decoder reached EOF and looping is disabled,
    /// or when the next frame is not due yet. Callers distinguish the two with
    /// [`FileSource::is_finished`].
    pub fn next(&mut self, now: Duration) -> Result<Option<Frame>, StreamError> {
        if self.finished {
            return Ok(None);
        }

        if self.pending.is_none() {
            match self.decoder.next_frame()? {
                Some(frame) => self.pending = Some(self.prepare(frame)),
                None => {
                    if !self.config.loop_playback {
                        self.finished = true;
                        return Ok(None);
                    }
                    self.restart()?;
                    match self.decoder.next_frame()? {
                        Some(frame) => self.pending = Some(self.prepare(frame)),
                        None => {
                            self.finished = true;
                            return Ok(None);
                        }
                    }
                }
            }
        }

        let due = match self.next_deadline {
            None => true,
            Some(deadline) => now >= deadline,
        };
        if !due {
            return Ok(None);
        }

        let frame = self.pending.take().expect("pending frame present");
        self.next_deadline = Some(
            self.next_deadline
                .map_or_else(|| now + self.interval, |deadline| deadline + self.interval),
        );
        Ok(Some(frame))
    }

    /// True once the decoder returned EOF and no further frame can be produced.
    ///
    /// A looping source only finishes when the decoder is empty, so callers can
    /// treat an empty `next()` after `is_finished()` as a permanent end.
    pub fn is_finished(&self) -> bool {
        self.finished
    }

    fn restart(&mut self) -> Result<(), StreamError> {
        self.decoder.rewind()?;
        self.loops += 1;
        // Carry the elapsed media time of the previous pass so that
        // `captured_at` stays monotonic across loop boundaries.
        self.loop_offset = self
            .last_pts
            .map_or(Duration::ZERO, |last| last.saturating_add(self.interval));
        // The next pass is normalised against its own first timestamp, so a
        // decoder that resumes PTS instead of rewinding still lines up.
        self.pass_first_pts = None;
        Ok(())
    }

    fn prepare(&mut self, decoded: DecodedFrame) -> Frame {
        let base = *self.pass_first_pts.get_or_insert(decoded.pts);
        let normalised = decoded
            .pts
            .saturating_sub(base)
            .saturating_add(self.loop_offset);

        if let Some(last) = self.last_pts {
            if normalised < last {
                self.rejected += 1;
            }
        }
        self.last_pts = Some(normalised);

        self.seq += 1;
        Frame::new(self.seq, normalised, decoded.width, decoded.height)
    }
}

/// Reconnect policy for a source that is expected to come back.
pub fn default_reconnect() -> Result<ReconnectPolicy, ReconnectError> {
    Ok(ReconnectPolicy::default_policy())
}

impl From<FileSourceError> for StreamError {
    fn from(error: FileSourceError) -> Self {
        match error {
            FileSourceError::Decode(detail) => StreamError::Decode { detail },
            other => StreamError::protocol(other.to_string()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Debug)]
    struct FakeDecoder {
        frames: Vec<DecodedFrame>,
        index: usize,
    }

    impl FakeDecoder {
        fn new(frames: Vec<DecodedFrame>) -> Self {
            Self { frames, index: 0 }
        }

        fn with_pts(pts_ms: &[u64]) -> Self {
            Self::new(
                pts_ms
                    .iter()
                    .map(|ms| DecodedFrame {
                        pts: Duration::from_millis(*ms),
                        width: 768,
                        height: 432,
                    })
                    .collect(),
            )
        }
    }

    impl FrameDecoder for FakeDecoder {
        fn next_frame(&mut self) -> Result<Option<DecodedFrame>, StreamError> {
            let frame = self.frames.get(self.index).cloned();
            self.index += 1;
            Ok(frame)
        }

        fn rewind(&mut self) -> Result<(), StreamError> {
            self.index = 0;
            Ok(())
        }
    }

    fn config(loop_playback: bool) -> FileSourceConfig {
        FileSourceConfig {
            path: "testdata/videos/mock.mp4".to_string(),
            fps: 25,
            loop_playback,
        }
    }

    #[test]
    fn rejects_zero_fps_and_empty_path() {
        let mut bad_fps = config(false);
        bad_fps.fps = 0;
        assert_eq!(
            FileSource::new(bad_fps, FakeDecoder::with_pts(&[0])).unwrap_err(),
            FileSourceError::ZeroFps
        );

        let mut bad_path = config(false);
        bad_path.path = "  ".to_string();
        assert_eq!(
            FileSource::new(bad_path, FakeDecoder::with_pts(&[0])).unwrap_err(),
            FileSourceError::EmptyPath
        );
    }

    #[test]
    fn normalises_first_timestamp_to_zero() {
        let mut source =
            FileSource::new(config(false), FakeDecoder::with_pts(&[1024, 1536])).unwrap();

        let first = source.next(Duration::ZERO).unwrap().unwrap();
        assert_eq!(first.captured_at, Duration::ZERO);
        assert_eq!(first.seq, 1);

        let second = source.next(Duration::from_millis(40)).unwrap().unwrap();
        assert_eq!(second.captured_at, Duration::from_millis(512));
        assert_eq!(second.seq, 2);
    }

    #[test]
    fn paces_frames_at_the_configured_interval() {
        let mut source =
            FileSource::new(config(false), FakeDecoder::with_pts(&[0, 40, 80])).unwrap();

        assert!(source.next(Duration::ZERO).unwrap().is_some());
        assert!(source.next(Duration::from_millis(10)).unwrap().is_none());
        assert!(source.next(Duration::from_millis(40)).unwrap().is_some());
        assert!(source.next(Duration::from_millis(79)).unwrap().is_none());
        assert!(source.next(Duration::from_millis(80)).unwrap().is_some());
    }

    #[test]
    fn stops_at_eof_without_loop() {
        let mut source = FileSource::new(config(false), FakeDecoder::with_pts(&[0])).unwrap();
        assert!(source.next(Duration::ZERO).unwrap().is_some());
        assert!(!source.is_finished());
        assert!(source.next(Duration::from_secs(1)).unwrap().is_none());
        assert!(source.is_finished());
        assert_eq!(source.loops(), 0);
    }

    #[test]
    fn loops_from_the_first_keyframe() {
        let mut source = FileSource::new(config(true), FakeDecoder::with_pts(&[0, 40])).unwrap();
        assert!(source.next(Duration::ZERO).unwrap().is_some());
        assert!(source.next(Duration::from_millis(40)).unwrap().is_some());
        assert!(
            source.next(Duration::from_millis(80)).unwrap().is_some(),
            "loop must restart immediately"
        );
        assert_eq!(source.loops(), 1);
    }

    #[test]
    fn a_finished_source_does_not_poll_the_decoder_again() {
        let decoder = FakeDecoder::with_pts(&[0]);
        let mut source = FileSource::new(config(false), decoder).unwrap();

        assert!(source.next(Duration::ZERO).unwrap().is_some());
        assert!(source.next(Duration::from_secs(1)).unwrap().is_none());
        let reads_at_eof = source.decoder.index;

        for step in 1..5 {
            assert!(source
                .next(Duration::from_secs(1 + step))
                .unwrap()
                .is_none());
        }
        assert_eq!(
            source.decoder.index, reads_at_eof,
            "EOF must be sticky; the decoder is not polled again"
        );
    }

    #[test]
    fn looping_keeps_capture_time_monotonic() {
        let mut source = FileSource::new(config(true), FakeDecoder::with_pts(&[0, 40])).unwrap();

        let first = source.next(Duration::ZERO).unwrap().unwrap();
        let second = source.next(Duration::from_millis(40)).unwrap().unwrap();
        let third = source.next(Duration::from_millis(80)).unwrap().unwrap();

        assert_eq!(first.captured_at, Duration::ZERO);
        assert_eq!(second.captured_at, Duration::from_millis(40));
        assert_eq!(
            third.captured_at,
            Duration::from_millis(80),
            "first frame of the second pass must continue after the last frame"
        );
        assert!(first.captured_at < second.captured_at);
        assert!(second.captured_at < third.captured_at);
        assert_eq!(source.rejected(), 0);
        assert!(!source.is_finished(), "a looping source never finishes");
    }

    #[test]
    fn looping_discards_an_empty_decoder() {
        let mut source = FileSource::new(config(true), FakeDecoder::new(Vec::new())).unwrap();
        assert!(source.next(Duration::ZERO).unwrap().is_none());
        assert!(source.is_finished());
    }

    #[test]
    fn a_decoder_that_resumes_pts_still_keeps_capture_time_monotonic() {
        // Some demuxers do not reset PTS after a seek; `rewind` only moves the
        // read head. The source must normalise each pass against its own first
        // timestamp instead of assuming the PTS restarts near zero.
        struct ResumingDecoder {
            frames: Vec<DecodedFrame>,
            index: usize,
            base: Duration,
        }

        impl FrameDecoder for ResumingDecoder {
            fn next_frame(&mut self) -> Result<Option<DecodedFrame>, StreamError> {
                let frame = self.frames.get(self.index).map(|frame| DecodedFrame {
                    pts: frame.pts + self.base,
                    width: frame.width,
                    height: frame.height,
                });
                self.index += 1;
                Ok(frame)
            }

            fn rewind(&mut self) -> Result<(), StreamError> {
                self.index = 0;
                self.base += Duration::from_secs(10);
                Ok(())
            }
        }

        let decoder = ResumingDecoder {
            frames: FakeDecoder::with_pts(&[0, 40]).frames,
            index: 0,
            base: Duration::ZERO,
        };
        let mut source = FileSource::new(config(true), decoder).unwrap();

        let captures: Vec<Duration> = (0..4)
            .map(|step| {
                source
                    .next(Duration::from_millis(40 * step))
                    .unwrap()
                    .unwrap()
                    .captured_at
            })
            .collect();

        assert_eq!(
            captures,
            vec![
                Duration::ZERO,
                Duration::from_millis(40),
                Duration::from_millis(80),
                Duration::from_millis(120)
            ]
        );
        assert_eq!(source.rejected(), 0);
    }

    #[test]
    fn rejects_backwards_pts() {
        let mut source =
            FileSource::new(config(false), FakeDecoder::with_pts(&[0, 400, 80])).unwrap();
        assert!(source.next(Duration::ZERO).unwrap().is_some());
        assert!(source.next(Duration::from_millis(40)).unwrap().is_some());
        assert!(source.next(Duration::from_millis(80)).unwrap().is_some());
        assert_eq!(source.rejected(), 1);
    }
}
