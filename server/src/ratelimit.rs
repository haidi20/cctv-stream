//! Fixed-window rate limiting per device and route.
//!
//! A broken device retrying in a tight loop is the realistic threat on a 2 GB
//! VPS, not a distributed attacker (that is the reverse proxy's job). The window
//! is 60 seconds and the state lives in this process; a restart resets the
//! counters, which is acceptable because the client also backs off.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Routes that have their own per-device budget.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Route {
    /// `POST /v1/sync`.
    Sync,
    /// `POST /v1/heartbeat`.
    Heartbeat,
    /// `POST /v1/snapshot`.
    Snapshot,
}

/// Window length for every route.
pub const WINDOW: Duration = Duration::from_secs(60);

/// Outcome of a rate-limit check.
#[derive(Debug, PartialEq, Eq)]
pub enum LimitDecision {
    /// Request may proceed.
    Allowed,
    /// Budget exhausted; retry after the given whole seconds.
    Limited {
        /// Seconds until the window rolls over, at least 1.
        retry_after_s: u64,
    },
}

struct Bucket {
    window_start: Instant,
    used: u32,
}

/// Per-process limiter.
pub struct RateLimiter {
    limits: HashMap<Route, u32>,
    buckets: Mutex<HashMap<(String, Route), Bucket>>,
    window: Duration,
}

impl RateLimiter {
    /// Build a limiter with the documented per-minute budgets.
    pub fn new(sync_per_minute: u32, heartbeat_per_minute: u32, snapshot_per_minute: u32) -> Self {
        let mut limits = HashMap::new();
        limits.insert(Route::Sync, sync_per_minute);
        limits.insert(Route::Heartbeat, heartbeat_per_minute);
        limits.insert(Route::Snapshot, snapshot_per_minute);
        Self {
            limits,
            buckets: Mutex::new(HashMap::new()),
            window: WINDOW,
        }
    }

    /// Check and consume one unit for `device_id` on `route`.
    pub fn check(&self, device_id: &str, route: Route) -> LimitDecision {
        self.check_at(device_id, route, Instant::now())
    }

    /// Same as [`Self::check`] with an injectable clock for tests.
    pub fn check_at(&self, device_id: &str, route: Route, now: Instant) -> LimitDecision {
        let limit = self.limits.get(&route).copied().unwrap_or(0);
        if limit == 0 {
            return LimitDecision::Allowed;
        }

        let mut buckets = match self.buckets.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        let key = (device_id.to_string(), route);
        let bucket = buckets.entry(key).or_insert(Bucket {
            window_start: now,
            used: 0,
        });

        if now.duration_since(bucket.window_start) >= self.window {
            bucket.window_start = now;
            bucket.used = 0;
        }

        if bucket.used >= limit {
            let elapsed = now.duration_since(bucket.window_start);
            let remaining = self.window.saturating_sub(elapsed);
            let retry_after_s = remaining.as_secs().max(1);
            return LimitDecision::Limited { retry_after_s };
        }

        bucket.used += 1;
        LimitDecision::Allowed
    }

    /// Drop buckets whose window has already rolled over.
    pub fn prune(&self, now: Instant) {
        let mut buckets = match self.buckets.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        buckets.retain(|_, bucket| now.duration_since(bucket.window_start) < self.window);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allows_exactly_the_budget_then_limits() {
        let limiter = RateLimiter::new(2, 4, 2);
        let now = Instant::now();
        assert_eq!(
            limiter.check_at("dev", Route::Sync, now),
            LimitDecision::Allowed
        );
        assert_eq!(
            limiter.check_at("dev", Route::Sync, now),
            LimitDecision::Allowed
        );
        assert!(matches!(
            limiter.check_at("dev", Route::Sync, now),
            LimitDecision::Limited { .. }
        ));
    }

    #[test]
    fn budgets_are_per_route_and_per_device() {
        let limiter = RateLimiter::new(1, 1, 1);
        let now = Instant::now();
        assert_eq!(
            limiter.check_at("a", Route::Sync, now),
            LimitDecision::Allowed
        );
        assert_eq!(
            limiter.check_at("a", Route::Heartbeat, now),
            LimitDecision::Allowed
        );
        assert_eq!(
            limiter.check_at("b", Route::Sync, now),
            LimitDecision::Allowed
        );
    }

    #[test]
    fn the_window_rolls_over() {
        let limiter = RateLimiter::new(1, 1, 1);
        let start = Instant::now();
        assert_eq!(
            limiter.check_at("a", Route::Sync, start),
            LimitDecision::Allowed
        );
        assert!(matches!(
            limiter.check_at("a", Route::Sync, start),
            LimitDecision::Limited { .. }
        ));
        assert_eq!(
            limiter.check_at("a", Route::Sync, start + WINDOW),
            LimitDecision::Allowed
        );
    }

    #[test]
    fn retry_after_is_never_zero() {
        let limiter = RateLimiter::new(1, 1, 1);
        let start = Instant::now();
        let _ = limiter.check_at("a", Route::Sync, start);
        match limiter.check_at("a", Route::Sync, start + Duration::from_millis(59_900)) {
            LimitDecision::Limited { retry_after_s } => assert_eq!(retry_after_s, 1),
            other => panic!("expected limited, got {other:?}"),
        }
    }
}
