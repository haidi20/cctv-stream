//! Status transitions and the `unknown` rule when a stream fails.

use std::time::Duration;

use spatial_engine::{DebounceConfig, TableStateMachine, TableStatus};

fn table() -> TableStateMachine {
    TableStateMachine::new(
        "meja-01",
        DebounceConfig::new(Duration::from_secs(2)),
        Duration::ZERO,
    )
}

#[test]
fn occupied_requires_consistent_frames() {
    let mut table = table();
    assert!(table.observe(1, Duration::from_millis(0)).is_none());
    assert!(table.observe(0, Duration::from_millis(500)).is_none());
    assert!(table.observe(1, Duration::from_millis(1_000)).is_none());
    assert!(table.observe(1, Duration::from_millis(3_000)).is_some());
    assert_eq!(table.state().status, TableStatus::Occupied);
}

#[test]
fn since_changes_only_on_transition() {
    let mut table = table();
    table.observe(1, Duration::from_millis(0));
    table.observe(1, Duration::from_secs(2));
    let since = table.state().since;

    table.observe(1, Duration::from_secs(10));
    assert_eq!(table.state().since, since);
}

#[test]
fn stream_down_never_reports_available() {
    let mut table = table();
    table.observe(1, Duration::from_millis(0));
    table.observe(1, Duration::from_secs(2));

    let state = table.stream_down(Duration::from_millis(2_100)).unwrap();
    assert_eq!(state.status, TableStatus::Unknown);
    assert_ne!(state.status, TableStatus::Available);
}

#[test]
fn unknown_is_reported_once() {
    let mut table = table();
    table.stream_down(Duration::from_millis(0));
    assert!(table.stream_down(Duration::from_secs(1)).is_none());
    assert!(table.stream_down(Duration::from_secs(60)).is_none());
}

#[test]
fn stream_recovery_returns_to_available() {
    let mut table = table();
    table.stream_down(Duration::from_millis(0));
    let state = table.stream_up(Duration::from_secs(1)).unwrap();
    assert_eq!(state.status, TableStatus::Available);
}

#[test]
fn status_strings_match_the_json_contract() {
    assert_eq!(TableStatus::Available.as_str(), "available");
    assert_eq!(TableStatus::Occupied.as_str(), "occupied");
    assert_eq!(TableStatus::Unknown.as_str(), "unknown");
}
