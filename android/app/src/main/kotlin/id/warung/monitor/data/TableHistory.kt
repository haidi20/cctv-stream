package id.warung.monitor.data

import id.warung.monitor.core.model.SyncTableStatus
import id.warung.monitor.core.sync.RetentionWindow

/**
 * Append-only local history of table transitions (`docs/android-sync.md`
 * sections 4 and 6).
 *
 * History is written *after* the outbox row is durable and is allowed to fail:
 * losing a log entry must never lose the event the server still has to receive.
 * That is also why pruning lives on a separate seam — the recorder must not be
 * able to delete anything.
 */
interface TableHistory {

    suspend fun record(tableId: String, status: SyncTableStatus, eventAtMillis: Long)

    /**
     * Marks rows recorded at or before [eventAtMillis] as delivered.
     *
     * Only these rows may age out, so an offline device keeps every unsent
     * transition no matter how old it is.
     */
    suspend fun markSyncedUpTo(eventAtMillis: Long): Int

    /** Used as the default so the recorder works before Room is wired. */
    object None : TableHistory {
        override suspend fun record(tableId: String, status: SyncTableStatus, eventAtMillis: Long) = Unit

        override suspend fun markSyncedUpTo(eventAtMillis: Long): Int = 0
    }
}

/** The destructive half of the history store; only the prune worker holds it. */
interface TableHistoryPruner {

    /** Deletes by age then by row count and returns how many rows disappeared. */
    suspend fun prune(nowMillis: Long, window: RetentionWindow): Int
}
