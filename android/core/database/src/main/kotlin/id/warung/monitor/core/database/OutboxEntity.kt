package id.warung.monitor.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One table-status event waiting for `POST /v1/sync`.
 *
 * `seq` is `AUTOINCREMENT` on purpose: SQLite never reuses a number after a
 * row is deleted, so the server can keep detecting gaps and duplicates. The
 * payload is stored as the exact JSON that will be sent; re-encoding at send
 * time could change field order or rounding after the event was already
 * validated.
 *
 * Rows are only marked [isSynced] after an ACK. A crash between the request and
 * the ACK therefore replays the batch, which the server deduplicates on
 * `(device_id, seq)`.
 */
@Entity(
    tableName = "outbox",
    indices = [Index(value = ["is_synced", "seq"], name = "index_outbox_pending")],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "seq")
    val seq: Long = 0,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "payload")
    val payload: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false,
)
