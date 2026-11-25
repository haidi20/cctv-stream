//! Per-table status state machine.

use std::time::Duration;

use serde::Serialize;

use crate::debounce::{DebounceConfig, Debounced};

/// Status reported to the UI and the server.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum TableStatus {
    /// No person detected inside the table footprint.
    Available,
    /// A person has been detected for the debounce window.
    Occupied,
    /// Stream is down or the model is not ready; never report `available` here.
    Unknown,
}

impl TableStatus {
    /// Stable string form used in JSON.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Available => "available",
            Self::Occupied => "occupied",
            Self::Unknown => "unknown",
        }
    }
}

/// Status plus the time it became stable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TableState {
    /// Current status.
    pub status: TableStatus,
    /// Local time of the last transition.
    pub since: Duration,
}

/// State machine for one table.
#[derive(Debug, Clone)]
pub struct TableStateMachine {
    id: String,
    debounce: Debounced,
    state: TableState,
    persons: usize,
}

impl TableStateMachine {
    /// Create a machine that starts as `available`.
    pub fn new(id: impl Into<String>, config: DebounceConfig, now: Duration) -> Self {
        Self {
            id: id.into(),
            debounce: Debounced::new(config, false, now),
            state: TableState {
                status: TableStatus::Available,
                since: now,
            },
            persons: 0,
        }
    }

    /// Table identifier.
    pub fn id(&self) -> &str {
        &self.id
    }

    /// Current state.
    pub fn state(&self) -> TableState {
        self.state
    }

    /// People assigned to this table in the current frame.
    pub fn persons(&self) -> usize {
        self.persons
    }

    /// Feed the current frame's person count.
    pub fn observe(&mut self, persons: usize, now: Duration) -> Option<TableState> {
        self.persons = persons;
        if self.state.status == TableStatus::Unknown {
            return None;
        }

        let occupied = persons > 0;
        let flipped = self.debounce.observe(occupied, now)?;
        let status = if flipped {
            TableStatus::Occupied
        } else {
            TableStatus::Available
        };
        self.state = TableState {
            status,
            since: self.debounce.since(),
        };
        Some(self.state)
    }

    /// Stream went down: report `unknown`, never `available`.
    pub fn stream_down(&mut self, now: Duration) -> Option<TableState> {
        self.persons = 0;
        if self.state.status == TableStatus::Unknown {
            return None;
        }
        self.state = TableState {
            status: TableStatus::Unknown,
            since: now,
        };
        Some(self.state)
    }

    /// Stream recovered: restart from `available` and let debounce decide.
    pub fn stream_up(&mut self, now: Duration) -> Option<TableState> {
        self.debounce = Debounced::new(self.debounce.config(), false, now);
        if self.state.status == TableStatus::Available {
            self.state = TableState {
                status: TableStatus::Available,
                since: now,
            };
            return None;
        }

        self.state = TableState {
            status: TableStatus::Available,
            since: now,
        };
        Some(self.state)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn machine() -> TableStateMachine {
        TableStateMachine::new(
            "meja-01",
            DebounceConfig::new(Duration::from_secs(2)),
            Duration::ZERO,
        )
    }

    #[test]
    fn starts_available() {
        let table = machine();
        assert_eq!(table.state().status, TableStatus::Available);
    }

    #[test]
    fn reports_occupied_after_debounce() {
        let mut table = machine();
        assert!(table.observe(1, Duration::from_millis(0)).is_none());
        let change = table.observe(1, Duration::from_secs(2)).unwrap();
        assert_eq!(change.status, TableStatus::Occupied);
        assert_eq!(change.since, Duration::from_secs(2));
    }

    #[test]
    fn hold_does_not_change_since() {
        let mut table = machine();
        table.observe(1, Duration::from_millis(0));
        table.observe(1, Duration::from_secs(2));
        assert!(table.observe(1, Duration::from_secs(5)).is_none());
        assert_eq!(table.state().since, Duration::from_secs(2));
    }

    #[test]
    fn short_gap_does_not_flip_to_available() {
        let mut table = machine();
        table.observe(1, Duration::from_millis(0));
        table.observe(1, Duration::from_secs(2));
        assert!(table.observe(0, Duration::from_secs(3)).is_none());
        assert!(table.observe(1, Duration::from_secs(4)).is_none());
        assert_eq!(table.state().status, TableStatus::Occupied);
    }

    #[test]
    fn long_gap_flips_to_available() {
        let mut table = machine();
        table.observe(1, Duration::from_millis(0));
        table.observe(1, Duration::from_secs(2));
        assert!(table.observe(0, Duration::from_secs(3)).is_none());
        let change = table.observe(0, Duration::from_secs(5)).unwrap();
        assert_eq!(change.status, TableStatus::Available);
    }

    #[test]
    fn stream_down_reports_unknown_not_available() {
        let mut table = machine();
        table.observe(1, Duration::from_millis(0));
        table.observe(1, Duration::from_secs(2));
        assert_eq!(
            table
                .stream_down(Duration::from_millis(2_500))
                .unwrap()
                .status,
            TableStatus::Unknown
        );
        assert_eq!(
            table.stream_down(Duration::from_secs(30)).map(|s| s.status),
            None
        );
    }

    #[test]
    fn observations_are_ignored_while_unknown() {
        let mut table = machine();
        table.stream_down(Duration::from_millis(10));
        assert!(table.observe(3, Duration::from_secs(5)).is_none());
        assert_eq!(table.state().status, TableStatus::Unknown);
    }

    #[test]
    fn stream_up_returns_to_available_and_revives_debounce() {
        let mut table = machine();
        table.observe(1, Duration::from_millis(0));
        table.observe(1, Duration::from_secs(2));
        table.stream_down(Duration::from_secs(3));
        assert_eq!(
            table.stream_up(Duration::from_secs(4)).unwrap().status,
            TableStatus::Available
        );
        assert!(table.observe(1, Duration::from_secs(5)).is_none());
    }
}
