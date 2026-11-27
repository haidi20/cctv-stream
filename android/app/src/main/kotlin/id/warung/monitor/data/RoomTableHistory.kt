package id.warung.monitor.data

import id.warung.monitor.core.database.TableLogDao
import id.warung.monitor.core.database.TableLogEntity
import id.warung.monitor.core.model.SyncTableStatus
import id.warung.monitor.core.sync.LocalHistoryStats
import id.warung.monitor.core.sync.LocalHistoryStore
import id.warung.monitor.core.sync.RetentionPolicy
import id.warung.monitor.core.sync.RetentionWindow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Size of `monitor.db` on disk; a seam so tests never read the filesystem. */
fun interface DatabaseSize {
    fun bytes(): Long
}

/**
 * Room-backed `table_logs`.
 *
 * All three interfaces are served by one class because they touch the same
 * table: [TableHistory] writes rows on the event path, [TableHistoryPruner] is
 * handed only to the prune worker, and [LocalHistoryStore] is the read-only view
 * the storage screen shows. Writes happen only after the outbox row is durable,
 * so a history failure can never cost an event.
 */
@Singleton
class RoomTableHistory @Inject constructor(
    private val logs: TableLogDao,
    private val databaseSize: DatabaseSize,
) : TableHistory, TableHistoryPruner, LocalHistoryStore {

    override suspend fun record(tableId: String, status: SyncTableStatus, eventAtMillis: Long) {
        withContext(Dispatchers.IO) {
            logs.insert(
                TableLogEntity(
                    tableId = tableId,
                    status = status.wire,
                    eventAt = eventAtMillis.coerceAtLeast(0L),
                ),
            )
        }
    }

    override suspend fun markSyncedUpTo(eventAtMillis: Long): Int = withContext(Dispatchers.IO) {
        logs.markSynced(eventAtMillis)
    }

    override suspend fun prune(nowMillis: Long, window: RetentionWindow): Int =
        withContext(Dispatchers.IO) {
            val byAge = logs.pruneSyncedBefore(RetentionPolicy.cutoffMillis(nowMillis, window))
            // Runs after the age prune so the row cap also bounds a short window
            // on a device that produced a burst of transitions.
            val byCount = logs.pruneOverflow(RetentionPolicy.MAX_ROWS)
            byAge + byCount
        }

    override suspend fun stats(): LocalHistoryStats = withContext(Dispatchers.IO) {
        LocalHistoryStats(rows = logs.count(), databaseBytes = databaseSize.bytes())
    }
}
