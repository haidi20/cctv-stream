package id.warung.monitor.sync

import id.warung.monitor.core.sync.SyncCoordinator
import id.warung.monitor.data.OutboxRepository
import id.warung.monitor.data.TableHistory
import kotlinx.coroutines.CancellationException

/**
 * Binds the pure coordinator to Room. Kept separate so the coordinator stays
 * Android-free and unit-testable on a plain JVM.
 */
class OutboxSyncAdapter(
    private val outbox: OutboxRepository,
    private val history: TableHistory = TableHistory.None,
) : SyncCoordinator.Outbox {

    override suspend fun pending(limit: Int): List<SyncCoordinator.PendingEvent> =
        outbox.pending(limit).map { SyncCoordinator.PendingEvent(it.seq, it.payload) }

    /**
     * ACKs the outbox and then releases the matching history for pruning.
     *
     * The bound is the `created_at` of the newest acknowledged row. Outbox
     * writes are serialized, so every history row written at or before it
     * belongs to an event the server has stored; newer rows stay unsynced.
     */
    override suspend fun acknowledge(ackedSeq: Long): Int {
        val affected = outbox.acknowledge(ackedSeq)
        if (affected == 0) return 0
        try {
            outbox.lastAckedCreatedAt(ackedSeq)?.let { bound -> history.markSyncedUpTo(bound) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // History lag is harmless: unsynced rows are simply kept longer.
        }
        return affected
    }

    override suspend fun recordFailure(seq: Long, message: String): Unit =
        outbox.recordFailure(seq, message)
}

