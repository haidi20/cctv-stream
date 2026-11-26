//! Persistence. Every SQL statement in the server lives here.
//!
//! The `seq` rules are the heart of idempotency (`docs/server-implementation.md`
//! section 4). A batch carries `seq` of its **last** event and `N` contiguous
//! events, so the first event in the batch is `seq - (N - 1)`. The server keeps
//! `acked_seq` per device and accepts only the next contiguous range.

use std::path::Path;
use std::time::Duration;

use serde_json::Value;
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous};
use sqlx::{Row, SqlitePool};
use thiserror::Error;

use crate::model::{now_utc, parse_utc};

/// Storage failures. The display form is logged, never returned to a client.
#[derive(Debug, Error)]
pub enum StoreError {
    /// The database rejected a query.
    #[error("database error: {0}")]
    Sqlx(#[from] sqlx::Error),
    /// A migration file failed.
    #[error("migration error: {0}")]
    Migrate(#[from] sqlx::migrate::MigrateError),
    /// A stored timestamp could not be parsed; this is a data bug.
    #[error("stored timestamp is not RFC 3339: {0}")]
    BadTimestamp(String),
}

/// Result of an idempotent batch insert.
#[derive(Debug, PartialEq, Eq)]
pub enum SyncOutcome {
    /// Batch accepted; `duplicates` events were already stored.
    Accepted {
        /// Number of events in the batch that already existed.
        duplicates: i64,
    },
    /// The batch starts after the next expected sequence.
    Gap {
        /// First sequence the device must send next.
        expected_seq: i64,
    },
    /// The batch refers to a sequence the device could never have produced.
    InvalidSequence,
}

/// One row of `table_events` that should be broadcast to dashboards.
#[derive(Debug, Clone)]
pub struct BroadcastEvent {
    /// Monotonic id for `resume_from`.
    pub event_id: i64,
    /// Device that produced the event.
    pub device_id: String,
    /// Table id.
    pub table_id: String,
    /// Wire status.
    pub status: String,
    /// Time the status began, RFC 3339 UTC.
    pub since: String,
}

/// A table in a snapshot, already validated against the shared schema.
#[derive(Debug, Clone)]
pub struct SnapshotTable {
    /// Table id.
    pub table_id: String,
    /// Wire status.
    pub status: String,
    /// Time the status began, RFC 3339 UTC.
    pub since: String,
}

/// One event from a sync batch, already validated against the shared schema.
#[derive(Debug, Clone)]
pub struct SyncEvent {
    /// Table id.
    pub table_id: String,
    /// Wire status.
    pub status: String,
    /// Time the status began, RFC 3339 UTC.
    pub since: String,
    /// Model confidence, when reported.
    pub confidence: Option<f64>,
    /// People assigned to the table, when reported.
    pub persons_in_region: Option<i64>,
}

/// A conflict between a snapshot and the sequenced history.
#[derive(Debug, Clone)]
pub struct SnapshotConflict {
    /// Table whose state disagreed.
    pub table_id: String,
    /// Status the snapshot claimed.
    pub snapshot_status: String,
    /// Status the ordered history had.
    pub current_status: String,
}

/// Database handle.
#[derive(Debug, Clone)]
pub struct Db {
    pool: SqlitePool,
}

impl Db {
    /// Open (or create) a SQLite database and run migrations.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] when the file cannot be opened or a migration
    /// fails. WAL and `NORMAL` sync keep the write path fast while staying
    /// durable across process crashes.
    pub async fn open(path: &Path) -> Result<Self, StoreError> {
        let options = SqliteConnectOptions::new()
            .filename(path)
            .create_if_missing(true)
            .journal_mode(SqliteJournalMode::Wal)
            .synchronous(SqliteSynchronous::Normal)
            .busy_timeout(Duration::from_secs(5));

        let pool = SqlitePoolOptions::new()
            .max_connections(8)
            .connect_with(options)
            .await?;
        sqlx::migrate!("./migrations").run(&pool).await?;
        Ok(Self { pool })
    }

    /// Create a new device and return its id. The caller prints the token once.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] on a database failure. An existing device id is a
    /// constraint violation, not a silent update.
    pub async fn create_device(&self, device_id: &str, token_hash: &str) -> Result<(), StoreError> {
        sqlx::query(
            "INSERT INTO devices (device_id, token_hash, acked_seq, created_at) \
             VALUES (?, ?, 0, ?)",
        )
        .bind(device_id)
        .bind(token_hash)
        .bind(now_utc())
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    /// Look up the device that owns a token hash.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] on a database failure.
    pub async fn device_for_token(&self, token_hash: &str) -> Result<Option<String>, StoreError> {
        let row = sqlx::query("SELECT device_id FROM devices WHERE token_hash = ?")
            .bind(token_hash)
            .fetch_optional(&self.pool)
            .await?;
        Ok(row.map(|row| row.get::<String, _>("device_id")))
    }

    /// Insert a sync batch idempotently.
    ///
    /// `events` are `(table_id, status, since, confidence, persons)` tuples in
    /// ascending sequence order, already validated.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] on a database failure.
    pub async fn insert_sync_batch(
        &self,
        device_id: &str,
        seq: i64,
        sent_at: &str,
        events: &[SyncEvent],
    ) -> Result<(SyncOutcome, Vec<BroadcastEvent>), StoreError> {
        let count = i64::try_from(events.len()).unwrap_or(i64::MAX);
        let Some(first) = seq.checked_sub(count - 1) else {
            return Ok((SyncOutcome::InvalidSequence, Vec::new()));
        };
        if first < 1 {
            return Ok((SyncOutcome::InvalidSequence, Vec::new()));
        }

        let mut tx = self.pool.begin().await?;
        let row = sqlx::query("SELECT acked_seq FROM devices WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&mut *tx)
            .await?;
        let Some(row) = row else {
            // Auth already proved the device exists; a missing row is a race
            // with a manual delete, so treat it as an internal failure.
            return Err(StoreError::Sqlx(sqlx::Error::RowNotFound));
        };
        let acked_seq: i64 = row.get("acked_seq");
        let expected_first = acked_seq.saturating_add(1);

        if first > expected_first {
            tx.rollback().await?;
            return Ok((
                SyncOutcome::Gap {
                    expected_seq: expected_first,
                },
                Vec::new(),
            ));
        }

        let duplicates = expected_first.saturating_sub(first).min(count);
        let received_at = now_utc();
        let mut broadcast = Vec::with_capacity(events.len());

        for (index, event) in events.iter().enumerate() {
            let event_seq = first + i64::try_from(index).unwrap_or(0);
            let result = sqlx::query(
                "INSERT OR IGNORE INTO table_events \
                 (device_id, seq, table_id, status, since, confidence, persons_in_region, received_at) \
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(device_id)
            .bind(event_seq)
            .bind(&event.table_id)
            .bind(&event.status)
            .bind(&event.since)
            .bind(event.confidence)
            .bind(event.persons_in_region)
            .bind(&received_at)
            .execute(&mut *tx)
            .await?;

            if result.rows_affected() == 1 {
                let event_id: i64 = result.last_insert_rowid();
                sqlx::query(
                    "INSERT INTO table_state (device_id, table_id, status, since, source, updated_at) \
                     VALUES (?, ?, ?, ?, 'device', ?) \
                     ON CONFLICT (device_id, table_id) DO UPDATE SET \
                       status = excluded.status, since = excluded.since, \
                       source = excluded.source, updated_at = excluded.updated_at",
                )
                .bind(device_id)
                .bind(&event.table_id)
                .bind(&event.status)
                .bind(&event.since)
                .bind(&received_at)
                .execute(&mut *tx)
                .await?;
                broadcast.push(BroadcastEvent {
                    event_id,
                    device_id: device_id.to_string(),
                    table_id: event.table_id.clone(),
                    status: event.status.clone(),
                    since: event.since.clone(),
                });
            }
        }

        sqlx::query(
            "INSERT OR IGNORE INTO sync_batches \
             (device_id, seq, event_count, sent_at, received_at) VALUES (?, ?, ?, ?, ?)",
        )
        .bind(device_id)
        .bind(seq)
        .bind(count)
        .bind(sent_at)
        .bind(&received_at)
        .execute(&mut *tx)
        .await?;

        let new_acked = acked_seq.max(seq);
        sqlx::query("UPDATE devices SET acked_seq = ? WHERE device_id = ?")
            .bind(new_acked)
            .bind(device_id)
            .execute(&mut *tx)
            .await?;

        tx.commit().await?;
        Ok((SyncOutcome::Accepted { duplicates }, broadcast))
    }

    /// Store a heartbeat and return the new broadcast id.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] on a database failure.
    pub async fn insert_heartbeat(
        &self,
        device_id: &str,
        session_id: &str,
        sent_at: &str,
        payload: &Value,
    ) -> Result<i64, StoreError> {
        let received_at = now_utc();
        let skew = clock_skew_ms(sent_at, &received_at).unwrap_or(0);
        let result = sqlx::query(
            "INSERT INTO heartbeats \
             (device_id, session_id, sent_at, received_at, clock_skew_ms, payload) \
             VALUES (?, ?, ?, ?, ?, ?)",
        )
        .bind(device_id)
        .bind(session_id)
        .bind(sent_at)
        .bind(&received_at)
        .bind(skew)
        .bind(payload.to_string())
        .execute(&self.pool)
        .await?;
        Ok(result.last_insert_rowid())
    }

    /// Apply a snapshot. Returns the conflicts found, if any.
    ///
    /// The snapshot never deletes history: `table_events` is untouched and only
    /// `table_state` is rewritten, with `source = 'snapshot'`.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] on a database failure.
    pub async fn apply_snapshot(
        &self,
        device_id: &str,
        tables: &[SnapshotTable],
    ) -> Result<Vec<SnapshotConflict>, StoreError> {
        let mut tx = self.pool.begin().await?;
        let updated_at = now_utc();
        let mut conflicts = Vec::new();

        for table in tables {
            let current = sqlx::query(
                "SELECT status, since FROM table_state WHERE device_id = ? AND table_id = ?",
            )
            .bind(device_id)
            .bind(&table.table_id)
            .fetch_optional(&mut *tx)
            .await?;

            if let Some(row) = current {
                let current_status: String = row.get("status");
                let current_since: String = row.get("since");
                if current_status != table.status || current_since != table.since {
                    sqlx::query(
                        "INSERT INTO snapshot_conflicts \
                         (device_id, table_id, snapshot_status, current_status, detected_at) \
                         VALUES (?, ?, ?, ?, ?)",
                    )
                    .bind(device_id)
                    .bind(&table.table_id)
                    .bind(&table.status)
                    .bind(&current_status)
                    .bind(&updated_at)
                    .execute(&mut *tx)
                    .await?;
                    conflicts.push(SnapshotConflict {
                        table_id: table.table_id.clone(),
                        snapshot_status: table.status.clone(),
                        current_status,
                    });
                }
            }

            sqlx::query(
                "INSERT INTO table_state (device_id, table_id, status, since, source, updated_at) \
                 VALUES (?, ?, ?, ?, 'snapshot', ?) \
                 ON CONFLICT (device_id, table_id) DO UPDATE SET \
                   status = excluded.status, since = excluded.since, \
                   source = excluded.source, updated_at = excluded.updated_at",
            )
            .bind(device_id)
            .bind(&table.table_id)
            .bind(&table.status)
            .bind(&table.since)
            .bind(&updated_at)
            .execute(&mut *tx)
            .await?;
        }

        tx.commit().await?;
        Ok(conflicts)
    }

    /// Count table events for a device; used by tests and diagnostics.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] on a database failure.
    pub async fn count_events(&self, device_id: &str) -> Result<i64, StoreError> {
        let row = sqlx::query("SELECT COUNT(*) AS total FROM table_events WHERE device_id = ?")
            .bind(device_id)
            .fetch_one(&self.pool)
            .await?;
        Ok(row.get("total"))
    }

    /// Current `acked_seq` for a device.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] on a database failure.
    pub async fn acked_seq(&self, device_id: &str) -> Result<i64, StoreError> {
        let row = sqlx::query("SELECT acked_seq FROM devices WHERE device_id = ?")
            .bind(device_id)
            .fetch_one(&self.pool)
            .await?;
        Ok(row.get("acked_seq"))
    }

    /// Count stored heartbeats for a device.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] on a database failure.
    pub async fn count_heartbeats(&self, device_id: &str) -> Result<i64, StoreError> {
        let row = sqlx::query("SELECT COUNT(*) AS total FROM heartbeats WHERE device_id = ?")
            .bind(device_id)
            .fetch_one(&self.pool)
            .await?;
        Ok(row.get("total"))
    }

    /// Current status of one table, if the device ever reported it.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] on a database failure.
    pub async fn table_status(
        &self,
        device_id: &str,
        table_id: &str,
    ) -> Result<Option<String>, StoreError> {
        let row =
            sqlx::query("SELECT status FROM table_state WHERE device_id = ? AND table_id = ?")
                .bind(device_id)
                .bind(table_id)
                .fetch_optional(&self.pool)
                .await?;
        Ok(row.map(|row| row.get::<String, _>("status")))
    }

    /// Count recorded snapshot conflicts for a device.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] on a database failure.
    pub async fn count_conflicts(&self, device_id: &str) -> Result<i64, StoreError> {
        let row =
            sqlx::query("SELECT COUNT(*) AS total FROM snapshot_conflicts WHERE device_id = ?")
                .bind(device_id)
                .fetch_one(&self.pool)
                .await?;
        Ok(row.get("total"))
    }

    /// Cheap liveness probe for `/health`.
    ///
    /// # Errors
    ///
    /// Returns [`StoreError`] when the database cannot answer.
    pub async fn ping(&self) -> Result<(), StoreError> {
        sqlx::query("SELECT 1").execute(&self.pool).await?;
        Ok(())
    }

    /// Raw pool handle, for integration tests that assert on stored rows.
    ///
    /// Production code must keep using the methods above; this exists so a test
    /// can prove the token never reached the payload it inspects.
    pub fn pool_for_tests(&self) -> &SqlitePool {
        &self.pool
    }
}

/// Difference between a device timestamp and server time, in milliseconds.
fn clock_skew_ms(sent_at: &str, received_at: &str) -> Option<i64> {
    let sent = chrono::DateTime::parse_from_rfc3339(&parse_utc(sent_at)?).ok()?;
    let received = chrono::DateTime::parse_from_rfc3339(&parse_utc(received_at)?).ok()?;
    Some((received - sent).num_milliseconds())
}
