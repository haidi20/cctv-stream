package id.warung.monitor.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One local record of a table transition (`docs/android-sync.md` section 4).
 *
 * `table_logs` is deliberately separate from `outbox`: the outbox is the
 * delivery queue that must never lose an unacknowledged event, while this table
 * is prunable history. Deleting history therefore cannot drop an event before
 * the server ACKs it.
 *
 * [syncStatus] mirrors "the matching event reached the server": rows are
 * inserted as `false` and flipped by [TableLogDao.markSynced] when the sync
 * worker acknowledges the outbox. Only synced rows age out, so a device that
 * has been offline for a month keeps its unsent evidence.
 */
@Entity(
    tableName = "table_logs",
    indices = [Index(value = ["sync_status", "event_at"], name = "index_table_logs_prune")],
)
data class TableLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "table_id")
    val tableId: String,
    @ColumnInfo(name = "status")
    val status: String,
    /** Wall-clock epoch millis, never monotonic `since`: history is not replay. */
    @ColumnInfo(name = "event_at")
    val eventAt: Long,
    @ColumnInfo(name = "sync_status")
    val syncStatus: Boolean = false,
)
