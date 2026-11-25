//! Time-based debounce for table status changes.

use std::time::Duration;

/// Debounce configuration.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DebounceConfig {
    /// How long a new observation must hold before it is accepted.
    pub hold: Duration,
}

impl DebounceConfig {
    /// Build a configuration.
    pub fn new(hold: Duration) -> Self {
        Self { hold }
    }

    /// Default 2 second hold, matching observed seating time.
    pub fn default_hold() -> Self {
        Self::new(Duration::from_secs(2))
    }
}

/// A single debounced boolean signal.
#[derive(Debug, Clone)]
pub struct Debounced {
    config: DebounceConfig,
    stable: bool,
    candidate: Option<(bool, Duration)>,
    stable_since: Duration,
}

impl Debounced {
    /// Create a debouncer with an initial stable value.
    pub fn new(config: DebounceConfig, initial: bool, now: Duration) -> Self {
        Self {
            config,
            stable: initial,
            candidate: None,
            stable_since: now,
        }
    }

    /// Configuration in use.
    pub fn config(&self) -> DebounceConfig {
        self.config
    }

    /// Current stable value.
    pub fn value(&self) -> bool {
        self.stable
    }

    /// When the stable value last changed.
    pub fn since(&self) -> Duration {
        self.stable_since
    }

    /// Feed an observation; returns `Some(new_value)` when the stable value flips.
    pub fn observe(&mut self, raw: bool, now: Duration) -> Option<bool> {
        if raw == self.stable {
            self.candidate = None;
            return None;
        }

        if self.config.hold.is_zero() {
            self.stable = raw;
            self.stable_since = now;
            self.candidate = None;
            return Some(raw);
        }

        match self.candidate {
            Some((pending, since)) if pending == raw => {
                if now.saturating_sub(since) >= self.config.hold {
                    self.stable = raw;
                    self.stable_since = now;
                    self.candidate = None;
                    Some(raw)
                } else {
                    None
                }
            }
            _ => {
                self.candidate = Some((raw, now));
                None
            }
        }
    }

    /// Force the stable value, for example when the stream goes down.
    pub fn force(&mut self, value: bool, now: Duration) -> Option<bool> {
        if self.stable == value {
            return None;
        }
        self.stable = value;
        self.stable_since = now;
        self.candidate = None;
        Some(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> DebounceConfig {
        DebounceConfig::new(Duration::from_secs(2))
    }

    #[test]
    fn single_noise_frame_does_not_flip() {
        let mut debounce = Debounced::new(config(), false, Duration::ZERO);
        assert_eq!(debounce.observe(true, Duration::from_millis(100)), None);
        assert_eq!(debounce.observe(false, Duration::from_millis(150)), None);
        assert!(!debounce.value());
    }

    #[test]
    fn sustained_change_flips_after_hold() {
        let mut debounce = Debounced::new(config(), false, Duration::ZERO);
        assert_eq!(debounce.observe(true, Duration::from_millis(0)), None);
        assert_eq!(debounce.observe(true, Duration::from_millis(1_900)), None);
        assert_eq!(
            debounce.observe(true, Duration::from_millis(2_000)),
            Some(true)
        );
        assert!(debounce.value());
        assert_eq!(debounce.since(), Duration::from_millis(2_000));
    }

    #[test]
    fn candidate_restarts_after_contradiction() {
        let mut debounce = Debounced::new(config(), false, Duration::ZERO);
        assert_eq!(debounce.observe(true, Duration::from_millis(0)), None);
        assert_eq!(
            debounce.observe(true, Duration::from_millis(2_000)),
            Some(true)
        );
        assert!(debounce.value());

        assert_eq!(debounce.observe(false, Duration::from_millis(2_100)), None);
        assert_eq!(debounce.observe(true, Duration::from_millis(3_000)), None);
        assert_eq!(
            debounce.observe(false, Duration::from_millis(3_100)),
            None,
            "a contradiction must restart the candidate window"
        );
        assert!(debounce.value(), "value must stay true until 2s of false");
        assert_eq!(
            debounce.observe(false, Duration::from_millis(5_100)),
            Some(false),
            "2s after the restarted window the value flips"
        );
    }

    #[test]
    fn force_overrides_immediately() {
        let mut debounce = Debounced::new(config(), false, Duration::ZERO);
        assert_eq!(debounce.force(true, Duration::from_millis(50)), Some(true));
        assert_eq!(debounce.force(true, Duration::from_millis(60)), None);
    }
}
