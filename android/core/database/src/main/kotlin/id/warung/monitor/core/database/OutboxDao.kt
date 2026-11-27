package id.warung.monitor.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface OutboxDao {

    /** Appends one event and returns its AUTOINCREMENT sequence. */
    @Insert
    suspend fun insert(row: OutboxEntity): Long

    /** Oldest unsynced rows first; the caller turns them into one batch. */
    @Query(
        "SELECT * FROM outbox WHERE is_synced = 0 " +
            "ORDER BY seq ASC LIMIT :limit",
    )
    suspend fun pending(limit: Int): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE is_synced = 0")
    suspend fun pendingCount(): Int

    @Query("SELECT MAX(seq) FROM outbox WHERE is_synced = 0")
    suspend fun pendingMaxSeq(): Long?

    /**
     * Marks every event up to the server's `acked_seq` as delivered.
     *
     * Rows stay until [pruneSynced] runs: keeping them briefly makes a bad ACK
     * (or a server reset) auditable without replaying them as pending.
     */
    @Query(
        "UPDATE outbox SET is_synced = 1 " +
            "WHERE device_id = :deviceId AND seq <= :ackedSeq AND is_synced = 0",
    )
    suspend fun markSynced(deviceId: String, ackedSeq: Long): Int

    /**
     * `created_at` of the newest acknowledged row up to [ackedSeq].
     *
     * History rows written at or before this instant describe events the server
     * already has, so they may age out. Returns null when nothing was ACKed.
     */
    @Query("SELECT MAX(created_at) FROM outbox WHERE seq <= :ackedSeq AND is_synced = 1")
    suspend fun maxAckedCreatedAt(ackedSeq: Long): Long?

    /** Bounded housekeeping; never touches rows that were not ACKed. */
    @Query("DELETE FROM outbox WHERE is_synced = 1 AND created_at < :cutoffMillis")
    suspend fun pruneSynced(cutoffMillis: Long): Int

    @Query("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE seq = :seq")
    suspend fun recordFailure(seq: Long, error: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()

    @Transaction
    suspend fun acknowledge(deviceId: String, ackedSeq: Long): Int =
        markSynced(deviceId, ackedSeq)
}
