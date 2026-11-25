//! Backoff schedule that prevents camera hammering.

use std::time::Duration;

use stream_engine::{ReconnectPolicy, DEFAULT_BACKOFF_SCHEDULE_MS};

#[test]
fn published_schedule_is_one_two_four_eight_sixteen() {
    assert_eq!(
        DEFAULT_BACKOFF_SCHEDULE_MS,
        [1_000, 2_000, 4_000, 8_000, 16_000]
    );
}

#[test]
fn a_long_outage_never_waits_less_than_sixteen_seconds() {
    let mut policy = ReconnectPolicy::default_policy();
    for _ in 0..50 {
        let _ = policy.next_delay();
    }
    assert!(policy.is_saturated());
    assert_eq!(policy.next_delay(), Duration::from_secs(16));
}

#[test]
fn successful_connect_restarts_the_schedule() {
    let mut policy = ReconnectPolicy::default_policy();
    let _ = policy.next_delay();
    let _ = policy.next_delay();
    let _ = policy.next_delay();

    policy.reset();

    assert_eq!(policy.next_delay(), Duration::from_secs(1));
}

#[test]
fn jitter_stays_inside_the_documented_band() {
    let base = Duration::from_secs(8);
    for attempt in 0..100 {
        let delay = ReconnectPolicy::with_deterministic_jitter(base, attempt, 100);
        assert!(delay >= Duration::from_millis(7_200));
        assert!(delay <= Duration::from_millis(8_800));
    }
}
