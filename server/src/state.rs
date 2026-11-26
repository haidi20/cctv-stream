//! Shared state: database, rate limiter, WebSocket ring buffer.
//!
//! `AppState` is cheap to clone (pool handle + Arc'd pieces), which is what
//! Axum handlers expect.

use std::sync::{Arc, Mutex};
use std::time::Instant;

use tokio::sync::broadcast;

use crate::config::Config;
use crate::model::WsMessage;
use crate::ratelimit::RateLimiter;
use crate::store::Db;

/// How many messages a reconnecting dashboard can replay.
pub const WS_BUFFER: usize = 256;

/// One buffered WebSocket message.
#[derive(Debug, Clone)]
pub struct BufferedMessage {
    /// Monotonic id, also used as `resume_from`.
    pub event_id: i64,
    /// Serialised message.
    pub message: WsMessage,
}

/// State shared by every request.
#[derive(Clone)]
pub struct AppState {
    /// Database handle.
    pub store: Arc<Db>,
    /// Per-device rate limits.
    pub limiter: Arc<RateLimiter>,
    /// Broadcast channel for WebSocket clients.
    pub broadcaster: broadcast::Sender<BufferedMessage>,
    /// Last [`WS_BUFFER`] messages, for `resume_from`.
    pub buffer: Arc<Mutex<Vec<BufferedMessage>>>,
    /// Monotonic id source for WebSocket messages.
    pub next_event_id: Arc<Mutex<i64>>,
    /// When the process started, for `/health`.
    pub started: Instant,
    /// Limits and body cap.
    pub config: Arc<Config>,
    /// Version reported by `/health`.
    pub version: &'static str,
}

impl AppState {
    /// Build state from an opened database.
    pub fn new(store: Db, config: Config, version: &'static str) -> Self {
        let limiter = RateLimiter::new(
            config.sync_per_minute,
            config.heartbeat_per_minute,
            config.snapshot_per_minute,
        );
        let (broadcaster, _) = broadcast::channel(WS_BUFFER);
        Self {
            store: Arc::new(store),
            limiter: Arc::new(limiter),
            broadcaster,
            buffer: Arc::new(Mutex::new(Vec::with_capacity(WS_BUFFER))),
            next_event_id: Arc::new(Mutex::new(0)),
            started: Instant::now(),
            config: Arc::new(config),
            version,
        }
    }

    /// Allocate the next WebSocket event id.
    pub fn next_id(&self) -> i64 {
        let mut next = match self.next_event_id.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        *next += 1;
        *next
    }

    /// Publish a message to live clients and the replay buffer.
    pub fn publish(&self, message: WsMessage) {
        let event_id = self.next_id();
        let message = message.with_event_id(event_id);

        {
            let mut buffer = match self.buffer.lock() {
                Ok(guard) => guard,
                Err(poisoned) => poisoned.into_inner(),
            };
            if buffer.len() >= WS_BUFFER {
                buffer.remove(0);
            }
            buffer.push(BufferedMessage {
                event_id,
                message: message.clone(),
            });
        }
        let _ = self.broadcaster.send(BufferedMessage { event_id, message });
    }

    /// Messages strictly after `resume_from`, or `None` when that id is gone.
    pub fn replay_since(&self, resume_from: i64) -> Option<Vec<WsMessage>> {
        let buffer = match self.buffer.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        let Some(first) = buffer.first() else {
            return Some(Vec::new());
        };
        if resume_from < first.event_id.saturating_sub(1) {
            return None;
        }
        Some(
            buffer
                .iter()
                .filter(|buffered| buffered.event_id > resume_from)
                .map(|buffered| buffered.message.clone())
                .collect(),
        )
    }
}
