//! Stream health counters exposed to the heartbeat payload.

use std::time::Duration;

/// Snapshot of stream-level counters.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct StreamMetrics {
    /// Frames decoded since start.
    pub frames_in: u64,
    /// Frames dropped by the sampler.
    pub frames_dropped: u64,
    /// Successful connections since start.
    pub connects: u32,
    /// Failed connection attempts since start.
    pub reconnect_failures: u32,
    /// Age of the newest frame; `None` before the first frame.
    pub last_frame_age: Option<Duration>,
    /// Local time when the newest frame was recorded.
    last_frame_at: Option<Duration>,
}

impl StreamMetrics {
    /// Record a decoded frame at `now`.
    pub fn record_frame(&mut self, now: Duration) {
        self.frames_in = self.frames_in.saturating_add(1);
        self.last_frame_age = Some(Duration::ZERO);
        self.last_frame_at = Some(now);
    }

    /// Recompute frame age at `now`.
    pub fn refresh_age(&mut self, now: Duration) {
        self.last_frame_age = self.last_frame_at.map(|seen| now.saturating_sub(seen));
    }

    /// Record a successful connect.
    pub fn record_connect(&mut self) {
        self.connects = self.connects.saturating_add(1);
        self.reconnect_failures = 0;
    }

    /// Record a failed connect attempt.
    pub fn record_failure(&mut self) {
        self.reconnect_failures = self.reconnect_failures.saturating_add(1);
    }

    /// Frames per second over a window.
    pub fn fps(&self, window: Duration) -> f64 {
        if window.is_zero() {
            return 0.0;
        }
        self.frames_in as f64 / window.as_secs_f64()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tracks_frame_age() {
        let mut metrics = StreamMetrics::default();
        metrics.record_frame(Duration::from_secs(10));
        metrics.refresh_age(Duration::from_secs(13));
        assert_eq!(metrics.last_frame_age, Some(Duration::from_secs(3)));
        assert_eq!(metrics.frames_in, 1);
    }

    #[test]
    fn resets_failures_after_connect() {
        let mut metrics = StreamMetrics::default();
        metrics.record_failure();
        metrics.record_failure();
        assert_eq!(metrics.reconnect_failures, 2);
        metrics.record_connect();
        assert_eq!(metrics.reconnect_failures, 0);
        assert_eq!(metrics.connects, 1);
    }
}
