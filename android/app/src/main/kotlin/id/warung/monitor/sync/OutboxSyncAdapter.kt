package id.warung.monitor.sync

import id.warung.monitor.core.sync.SyncCoordinator
import id.warung.monitor.data.OutboxRepository

/**
 * Binds the pure coordinator to Room. Kept separate so the coordinator stays
 * Android-free and unit-testable on a plain JVM.
 */
class OutboxSyncAdapter(
    private val outbox: OutboxRepository,
) : SyncCoordinator.Outbox {

    override suspend fun pending(limit: Int): List<SyncCoordinator.PendingEvent> =
        outbox.pending(limit).map { SyncCoordinator.PendingEvent(it.seq, it.payload) }

    override suspend fun acknowledge(ackedSeq: Long): Int = outbox.acknowledge(ackedSeq)

    override suspend fun recordFailure(seq: Long, message: String): Unit =
        outbox.recordFailure(seq, message)
}

