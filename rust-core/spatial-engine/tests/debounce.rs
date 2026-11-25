//! Debounce semantics with injected time.

use std::time::Duration;

use spatial_engine::{DebounceConfig, Debounced};

#[test]
fn missing_frames_do_not_flip_without_the_hold_window() {
    let mut debounce = Debounced::new(
        DebounceConfig::new(Duration::from_secs(3)),
        false,
        Duration::ZERO,
    );
    assert!(debounce.observe(true, Duration::from_millis(0)).is_none());
    assert!(debounce.observe(true, Duration::from_secs(2)).is_none());
    assert_eq!(debounce.observe(true, Duration::from_secs(3)), Some(true));
}

#[test]
fn a_single_dropped_frame_does_not_reset_a_stable_state() {
    let mut debounce = Debounced::new(
        DebounceConfig::new(Duration::from_secs(2)),
        true,
        Duration::ZERO,
    );
    assert!(debounce
        .observe(false, Duration::from_millis(100))
        .is_none());
    assert!(debounce.observe(true, Duration::from_millis(200)).is_none());
    assert!(debounce.value());
}

#[test]
fn force_is_used_for_stream_failures() {
    let mut debounce = Debounced::new(
        DebounceConfig::new(Duration::from_secs(2)),
        false,
        Duration::ZERO,
    );
    assert_eq!(debounce.force(true, Duration::from_millis(10)), Some(true));
    assert_eq!(debounce.since(), Duration::from_millis(10));
}

#[test]
fn zero_hold_flips_on_the_first_frame() {
    let mut debounce = Debounced::new(DebounceConfig::new(Duration::ZERO), false, Duration::ZERO);
    assert_eq!(debounce.observe(true, Duration::from_millis(1)), Some(true));
}
