//! Watchdog thread that turns a silent source into `unknown`.
//!
//! The engine is fed frames by Kotlin (`pushFrame`), so there is no blocking
//! RTSP loop here. A dedicated thread only checks whether frames stopped
//! arriving; if they did, every table is marked `unknown`. That distinction
//! matters: a camera that dies must not look like an empty restaurant.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crate::error::EngineError;
use crate::state::{lock, SharedState};
use crate::types::{StreamStatus, TableEvent};

/// Frames older than this are treated as a stalled stream.
pub const FRAME_STALE_AFTER: Duration = Duration::from_secs(5);

const WATCHDOG_TICK: Duration = Duration::from_millis(250);
/// How long `stop` waits for the watchdog to acknowledge shutdown.
const STOP_TIMEOUT: Duration = Duration::from_secs(2);

/// Callback invoked when the watchdog decides the stream is stale.
pub(crate) type OnStale = Arc<dyn Fn(StreamStatus, Vec<TableEvent>) + Send + Sync>;

enum Signal {
    Stop,
}

/// Owns the watchdog thread for one engine session.
pub(crate) struct Watchdog {
    stop_tx: Option<mpsc::Sender<Signal>>,
    handle: Option<JoinHandle<()>>,
}

impl Watchdog {
    /// Spawn the watchdog for one session.
    pub(crate) fn spawn(
        shared: SharedState,
        running: Arc<AtomicBool>,
        on_stale: OnStale,
    ) -> Result<Self, EngineError> {
        let (stop_tx, stop_rx) = mpsc::channel();
        let handle = std::thread::Builder::new()
            .name("cctv-watchdog".to_string())
            .spawn(move || run(shared, running, stop_rx, on_stale))
            .map_err(|error| EngineError::Config {
                reason: format!("cannot start watchdog: {error}"),
            })?;

        Ok(Self {
            stop_tx: Some(stop_tx),
            handle: Some(handle),
        })
    }

    /// Stop the watchdog and wait for it to exit.
    ///
    /// Idempotent; returns [`EngineError::StopTimeout`] only if the thread
    /// refuses to exit within [`STOP_TIMEOUT`].
    pub(crate) fn stop(&mut self) -> Result<(), EngineError> {
        let Some(handle) = self.handle.take() else {
            return Ok(());
        };

        if let Some(stop) = self.stop_tx.take() {
            let _ = stop.send(Signal::Stop);
        }

        let deadline = Instant::now() + STOP_TIMEOUT;
        while !handle.is_finished() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
        }

        if !handle.is_finished() {
            // There is no safe way to kill a thread. Dropping the handle keeps
            // the process alive; the report tells the caller the truth.
            return Err(EngineError::StopTimeout {
                timeout_ms: u64::try_from(STOP_TIMEOUT.as_millis()).unwrap_or(2_000),
            });
        }

        handle.join().map_err(|payload| {
            tracing::error!(
                event = "watchdog_panic",
                message = panic_message(payload.as_ref()),
                "watchdog panicked"
            );
            EngineError::Internal
        })?;
        Ok(())
    }
}

impl Drop for Watchdog {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}

fn run(
    shared: SharedState,
    running: Arc<AtomicBool>,
    stop_rx: Receiver<Signal>,
    on_stale: OnStale,
) {
    loop {
        match stop_rx.recv_timeout(WATCHDOG_TICK) {
            Ok(Signal::Stop) | Err(RecvTimeoutError::Disconnected) => break,
            Err(RecvTimeoutError::Timeout) => {}
        }

        if !running.load(Ordering::Acquire) {
            continue;
        }

        let (stale, events) = {
            let mut state = lock(&shared.inner);
            if state.stream != StreamStatus::Connected {
                continue;
            }
            let Some(last) = state.last_frame_at else {
                continue;
            };
            if last.elapsed() < FRAME_STALE_AFTER {
                continue;
            }
            let now = state
                .started_at
                .map_or(Duration::ZERO, |start| start.elapsed());
            let events = state.mark_stream_down(StreamStatus::Timeout, now);
            state.apply_events(&events);
            (true, events)
        };

        if stale {
            on_stale(StreamStatus::Timeout, events);
        }
    }
}

fn panic_message(payload: &(dyn std::any::Any + Send)) -> &str {
    payload
        .downcast_ref::<&str>()
        .copied()
        .or_else(|| payload.downcast_ref::<String>().map(String::as_str))
        .unwrap_or("non-string panic payload")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::{Counters, StateInner};
    use std::sync::Mutex;

    fn shared() -> SharedState {
        SharedState {
            inner: Arc::new(Mutex::new(StateInner::default())),
            counters: Arc::new(Counters::default()),
        }
    }

    #[test]
    fn stop_is_idempotent() {
        let running = Arc::new(AtomicBool::new(true));
        let mut watchdog =
            Watchdog::spawn(shared(), running, Arc::new(|_, _| {})).expect("spawn watchdog");
        assert_eq!(watchdog.stop(), Ok(()));
        assert_eq!(watchdog.stop(), Ok(()));
    }

    #[test]
    fn stale_frames_become_timeout_and_unknown() {
        let shared = shared();
        let running = Arc::new(AtomicBool::new(true));
        let reported = Arc::new(Mutex::new(None::<StreamStatus>));
        let sink = Arc::clone(&reported);

        {
            let mut state = lock(&shared.inner);
            state.begin_session(Instant::now());
            state.stream = StreamStatus::Connected;
            state.last_frame_at = Some(Instant::now() - FRAME_STALE_AFTER - Duration::from_secs(1));
        }

        let mut watchdog = Watchdog::spawn(
            shared,
            running,
            Arc::new(move |status, _| {
                *lock(&sink) = Some(status);
            }),
        )
        .unwrap();

        let deadline = Instant::now() + Duration::from_secs(3);
        while lock(&reported).is_none() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(20));
        }

        assert_eq!(*lock(&reported), Some(StreamStatus::Timeout));
        assert!(watchdog.stop().is_ok());
    }
}
