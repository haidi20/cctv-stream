//! Time-based frame sampling.

use std::time::Duration;

use thiserror::Error;

use crate::frame::Frame;

/// Recommended lower bound for table-occupancy monitoring.
pub const MIN_RECOMMENDED_FPS: f64 = 2.0;
/// Recommended upper bound for table-occupancy monitoring.
pub const MAX_RECOMMENDED_FPS: f64 = 5.0;

/// Invalid sampler configuration.
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum SamplerError {
    /// FPS must be finite and inside `0.5..=120`.
    #[error("invalid sampling fps {fps}")]
    InvalidFps {
        /// Rejected value.
        fps: String,
    },
}

/// Drops frames that arrive faster than the configured interval.
///
/// The sampler keeps only the newest pending frame, so a slow consumer never
/// builds a backlog. Non-monotonic timestamps are rejected instead of being
/// silently reordered.
#[derive(Debug)]
pub struct FrameSampler {
    interval: Duration,
    fps: f64,
    last_emit: Option<Duration>,
    last_seen: Option<Duration>,
    pending: Option<Frame>,
    dropped: u64,
    rejected: u64,
}

impl FrameSampler {
    /// Create a sampler for the given FPS.
    pub fn new(fps: f64) -> Result<Self, SamplerError> {
        if !fps.is_finite() || !(0.5..=120.0).contains(&fps) {
            return Err(SamplerError::InvalidFps {
                fps: fps.to_string(),
            });
        }

        Ok(Self {
            interval: Duration::from_secs_f64(1.0 / fps),
            fps,
            last_emit: None,
            last_seen: None,
            pending: None,
            dropped: 0,
            rejected: 0,
        })
    }

    /// Configured sampling rate.
    pub fn fps(&self) -> f64 {
        self.fps
    }

    /// Interval between emitted frames.
    pub fn interval(&self) -> Duration {
        self.interval
    }

    /// Whether the rate sits inside the recommended 2-5 FPS band.
    pub fn is_recommended(&self) -> bool {
        (MIN_RECOMMENDED_FPS..=MAX_RECOMMENDED_FPS).contains(&self.fps)
    }

    /// Frames replaced before they were emitted.
    pub fn dropped(&self) -> u64 {
        self.dropped
    }

    /// Frames rejected because their timestamp went backwards.
    pub fn rejected(&self) -> u64 {
        self.rejected
    }

    /// Offer a frame; returns it when it is due.
    pub fn push(&mut self, frame: Frame) -> Option<Frame> {
        if let Some(last) = self.last_seen {
            if frame.captured_at < last {
                self.rejected += 1;
                return None;
            }
        }

        self.last_seen = Some(frame.captured_at);
        if self.pending.replace(frame).is_some() {
            self.dropped += 1;
        }

        let pending = self.pending.as_ref()?;
        let due = match self.last_emit {
            None => true,
            Some(last) => pending.captured_at.saturating_sub(last) >= self.interval,
        };

        if due {
            self.emit()
        } else {
            None
        }
    }

    /// Drop any pending frame, for example when the source goes down.
    pub fn clear(&mut self) {
        self.pending = None;
    }

    fn emit(&mut self) -> Option<Frame> {
        let frame = self.pending.take()?;
        self.last_emit = Some(frame.captured_at);
        Some(frame)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(seq: u64, ms: u64) -> Frame {
        Frame::new(seq, Duration::from_millis(ms), 768, 432)
    }

    #[test]
    fn rejects_invalid_fps() {
        assert!(FrameSampler::new(0.0).is_err());
        assert!(FrameSampler::new(f64::NAN).is_err());
        assert!(FrameSampler::new(240.0).is_err());
    }

    #[test]
    fn emits_first_frame_immediately() {
        let mut sampler = FrameSampler::new(3.0).unwrap();
        assert_eq!(sampler.push(frame(1, 0)).map(|f| f.seq), Some(1));
        assert!(sampler.push(frame(2, 10)).is_none());
    }

    #[test]
    fn keeps_only_the_newest_pending_frame() {
        let mut sampler = FrameSampler::new(2.0).unwrap();
        assert_eq!(sampler.push(frame(1, 0)).map(|f| f.seq), Some(1));
        assert!(sampler.push(frame(2, 100)).is_none());
        assert!(sampler.push(frame(3, 200)).is_none());
        assert!(sampler.push(frame(4, 300)).is_none());

        let emitted = sampler.push(frame(5, 500)).expect("due at 500ms");
        assert_eq!(emitted.seq, 5);
        assert_eq!(sampler.dropped(), 3);
    }

    #[test]
    fn rejects_non_monotonic_timestamps() {
        let mut sampler = FrameSampler::new(3.0).unwrap();
        assert!(sampler.push(frame(1, 500)).is_some());
        assert!(sampler.push(frame(2, 400)).is_none());
        assert_eq!(sampler.rejected(), 1);
    }
}
