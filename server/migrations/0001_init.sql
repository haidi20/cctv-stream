-- Devices enrolled by `cctv-server enroll`. Only a SHA-256 of the token is
-- stored; the plaintext is printed once and never persisted here.
CREATE TABLE IF NOT EXISTS devices (
    device_id   TEXT PRIMARY KEY,
    token_hash  TEXT NOT NULL UNIQUE,
    acked_seq   INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL
);

-- One row per accepted batch. The unique key is what makes retries idempotent.
CREATE TABLE IF NOT EXISTS sync_batches (
    device_id     TEXT NOT NULL,
    seq           INTEGER NOT NULL,
    event_count   INTEGER NOT NULL,
    sent_at       TEXT NOT NULL,
    received_at   TEXT NOT NULL,
    PRIMARY KEY (device_id, seq)
);

-- Append-only history of table transitions. `seq` links an event back to the
-- batch that delivered it, which is how duplicates are detected.
CREATE TABLE IF NOT EXISTS table_events (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id          TEXT NOT NULL,
    seq                INTEGER NOT NULL,
    table_id           TEXT NOT NULL,
    status             TEXT NOT NULL,
    since              TEXT NOT NULL,
    confidence         REAL,
    persons_in_region  INTEGER,
    received_at        TEXT NOT NULL,
    UNIQUE (device_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_table_events_device_table
    ON table_events (device_id, table_id, id);

-- Latest state per table for the dashboard. Rewritten by sync and snapshot;
-- it never replaces the history in `table_events`.
CREATE TABLE IF NOT EXISTS table_state (
    device_id   TEXT NOT NULL,
    table_id    TEXT NOT NULL,
    status      TEXT NOT NULL,
    since       TEXT NOT NULL,
    source      TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    PRIMARY KEY (device_id, table_id)
);

-- A snapshot that disagrees with the sequenced history is recorded, not
-- silently resolved.
CREATE TABLE IF NOT EXISTS snapshot_conflicts (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id        TEXT NOT NULL,
    table_id         TEXT NOT NULL,
    snapshot_status  TEXT NOT NULL,
    current_status   TEXT NOT NULL,
    detected_at      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_snapshot_conflicts_device
    ON snapshot_conflicts (device_id, id);

-- Heartbeats are metadata only; the raw payload is kept for diagnosis so the
-- dashboard can render fields this server does not model yet.
CREATE TABLE IF NOT EXISTS heartbeats (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id      TEXT NOT NULL,
    session_id     TEXT NOT NULL,
    sent_at        TEXT NOT NULL,
    received_at    TEXT NOT NULL,
    clock_skew_ms  INTEGER NOT NULL,
    payload        TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_heartbeats_device
    ON heartbeats (device_id, id);