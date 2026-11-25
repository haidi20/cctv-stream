//! Deterministic exponential backoff for source reconnection.

use std::time::Duration;

use thiserror::Error;

/// Default backoff schedule: 1, 2, 4, 8, 16, then 16 seconds.
pub const DEFAULT_BACKOFF_SCHEDULE_MS: [u64; 5] = [1_000, 2_000, 4_000, 8_000, 16_000];

/// Invalid reconnect configuration.
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum ReconnectError {
    /// The schedule must not be empty; otherwise there is no delay to wait for.
    #[error("backoff schedule must not be empty")]
    EmptySchedule,
    /// Zero delays would produce a retry storm.
    #[error("backoff schedule contains a zero delay")]
    ZeroDelay,
}

/// Exponential backoff with a deterministic jitter source.
///
/// Jitter is a pure function of the attempt number
/// ([`ReconnectPolicy::with_deterministic_jitter`]), so tests can assert the
/// exact sequence and production stays reproducible across processes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReconnectPolicy {
    schedule: Vec<Duration>,
    attempts: u32,
}

impl ReconnectPolicy {
    /// Build a policy from an explicit schedule in milliseconds.
    pub fn from_millis(schedule: &[u64]) -> Result<Self, ReconnectError> {
        if schedule.is_empty() {
            return Err(ReconnectError::EmptySchedule);
        }
        if schedule.contains(&0) {
            return Err(ReconnectError::ZeroDelay);
        }

        Ok(Self {
            schedule: schedule
                .iter()
                .copied()
                .map(Duration::from_millis)
                .collect(),
            attempts: 0,
        })
    }

    /// Build the default 1-2-4-8-16 second policy.
    pub fn default_policy() -> Self {
        Self::from_millis(&DEFAULT_BACKOFF_SCHEDULE_MS).expect("default schedule is valid")
    }

    /// Number of failures observed since the last successful connection.
    pub fn attempts(&self) -> u32 {
        self.attempts
    }

    /// Delay before the next attempt. The last entry repeats forever.
    pub fn next_delay(&mut self) -> Duration {
        let index = (self.attempts as usize).min(self.schedule.len() - 1);
        self.attempts = self.attempts.saturating_add(1);
        self.schedule[index]
    }

    /// Reset the policy after a successful connection.
    pub fn reset(&mut self) {
        self.attempts = 0;
    }

    /// Delay for a specific attempt without mutating state.
    pub fn delay_for(&self, attempt: u32) -> Duration {
        let index = (attempt as usize).min(self.schedule.len() - 1);
        self.schedule[index]
    }

    /// True once the final schedule entry is in use.
    pub fn is_saturated(&self) -> bool {
        self.attempts as usize >= self.schedule.len()
    }

    /// Apply deterministic jitter of +/-`spread_permille` to a delay.
    ///
    /// `spread_permille = 100` means +/-10%. The same attempt number always
    /// yields the same delay.
    pub fn with_deterministic_jitter(
        delay: Duration,
        attempt: u32,
        spread_permille: u32,
    ) -> Duration {
        let spread = u64::from(spread_permille.min(500));
        if spread == 0 {
            return delay;
        }

        let hash = u64::from(attempt.wrapping_mul(2_654_435_761).rotate_left(13));
        let bucket = hash % (spread * 2 + 1);
        let offset = bucket as i128 - spread as i128;

        let base = delay.as_millis() as i128;
        let delta = base * offset / 1_000;
        Duration::from_millis(u64::try_from(base + delta).unwrap_or(1).max(1))
    }
}

impl Default for ReconnectPolicy {
    fn default() -> Self {
        Self::default_policy()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn follows_the_published_schedule() {
        let mut policy = ReconnectPolicy::default_policy();
        let delays: Vec<u64> = (0..6)
            .map(|_| policy.next_delay().as_millis() as u64)
            .collect();

        assert_eq!(delays, vec![1_000, 2_000, 4_000, 8_000, 16_000, 16_000]);
    }

    #[test]
    fn reset_starts_from_one_second() {
        let mut policy = ReconnectPolicy::default_policy();
        let _ = policy.next_delay();
        let _ = policy.next_delay();
        policy.reset();

        assert_eq!(policy.attempts(), 0);
        assert_eq!(policy.next_delay(), Duration::from_secs(1));
    }

    #[test]
    fn rejects_empty_and_zero_schedules() {
        assert_eq!(
            ReconnectPolicy::from_millis(&[]),
            Err(ReconnectError::EmptySchedule)
        );
        assert_eq!(
            ReconnectPolicy::from_millis(&[1_000, 0]),
            Err(ReconnectError::ZeroDelay)
        );
    }

    #[test]
    fn saturates_at_the_last_entry() {
        let mut policy = ReconnectPolicy::from_millis(&[500, 1_000]).unwrap();
        assert_eq!(policy.next_delay(), Duration::from_millis(500));
        assert_eq!(policy.next_delay(), Duration::from_millis(1_000));
        assert_eq!(policy.next_delay(), Duration::from_millis(1_000));
        assert!(policy.is_saturated());
    }

    #[test]
    fn jitter_is_deterministic_and_bounded() {
        let base = Duration::from_secs(4);
        for attempt in 0..32 {
            let first = ReconnectPolicy::with_deterministic_jitter(base, attempt, 100);
            let second = ReconnectPolicy::with_deterministic_jitter(base, attempt, 100);
            assert_eq!(first, second, "jitter must be deterministic");
            assert!(
                first >= Duration::from_millis(3_600),
                "jitter must stay >= -10%"
            );
            assert!(
                first <= Duration::from_millis(4_400),
                "jitter must stay <= +10%"
            );
        }
    }
}
