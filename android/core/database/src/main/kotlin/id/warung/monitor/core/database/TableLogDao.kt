package id.warung.monitor.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Local history of table transitions, plus the two prune queries from
 * `docs/android-sync.md` section 6.
 *
 * Every delete here is bounded: [pruneSyncedBefore] only removes rows already
 * acknowledged by the server and older than the user's retention window, and
 * [pruneOverflow] removes the oldest rows beyond the hard cap. Rows that were
 * never synced are kept so a long offline stretch does not erase evidence.
 */
@Dao
interface TableLogDao {

    @Insert
    suspend fun insert(row: TableLogEntity): Long

    @Query("SELECT COUNT(*) FROM table_logs")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM table_logs WHERE sync_status = 1")
    suspend fun syncedCount(): Int

    /**
     * Flips rows recorded at or before [eventAtUpTo] to synced.
     *
     * The bound comes from the `created_at` of the highest acknowledged outbox
     * row. Outbox writes are serialized, so every history row written before it
     * belongs to an event the server has already accepted.
     */
    @Query(
        "UPDATE table_logs SET sync_status = 1 " +
            "WHERE sync_status = 0 AND event_at <= :eventAtUpTo",
    )
    suspend fun markSynced(eventAtUpTo: Long): Int

    /** Age-based prune; never touches rows the server has not acknowledged. */
    @Query("DELETE FROM table_logs WHERE sync_status = 1 AND event_at < :cutoffMillis")
    suspend fun pruneSyncedBefore(cutoffMillis: Long): Int

    /** Row-count prune; FIFO so the newest transitions survive. */
    @Query(
        "DELETE FROM table_logs WHERE id NOT IN (" +
            "SELECT id FROM table_logs ORDER BY id DESC LIMIT :maxRows)",
    )
    suspend fun pruneOverflow(maxRows: Int): Int

    @Query("SELECT * FROM table_logs ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TableLogEntity>

    @Query("DELETE FROM table_logs")
    suspend fun clear(): Int
}
