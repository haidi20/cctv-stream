package id.warung.monitor.data

import id.warung.monitor.core.database.OutboxDao
import id.warung.monitor.core.database.OutboxEntity
import id.warung.monitor.core.model.TableStatusJson
import id.warung.monitor.core.model.TableStatusPayload
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the outbox looks like right now; used for UI and tests, never for sync logic. */
data class OutboxSnapshot(
    val pending: Int,
    val newestSeq: Long?,
)

/**
 * Append-only queue of table events that the server has not acknowledged.
 *
 * Writes happen **before** the UI is updated (`docs/android-sync.md` section 5):
 * if the process dies between the event and the notification, the event is still
 * on disk. Nothing here talks to the network; sending is a separate worker so
 * an offline device never blocks inference or rendering.
 *
 * The device id is resolved lazily and cached, because generating it is cheap
 * but reading it is on the event path. [clock] and [deviceId] are injectable so
 * tests can use a fixed time and a stable id.
 */
@Singleton
class OutboxRepository(
    private val dao: OutboxDao,
    private val deviceId: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Inject
    constructor(dao: OutboxDao, deviceIds: DeviceIdStore) : this(
        dao = dao,
        deviceId = deviceIds::deviceId,
    )

    private val resolvedDeviceId = AtomicReference<String?>(null)

    /**
     * Persists one event and returns its new `seq`.
     *
     * A write failure is not swallowed: the caller must know that the event is
     * not durable. `unknown` is encoded exactly like any other status, so a
     * camera outage never becomes "table available" on the dashboard.
     */
    suspend fun append(payload: TableStatusPayload): Long = withContext(Dispatchers.IO) {
        val device = resolveDeviceId()
        // Validate by encoding now, not at send time: an invalid payload must
        // never reach the queue where a worker would discover it later.
        val json = TableStatusJson.encode(payload)
        dao.insert(
            OutboxEntity(
                deviceId = device,
                payload = json,
                createdAt = clock(),
            ),
        )
    }

    suspend fun pending(limit: Int = MAX_BATCH): List<OutboxEntity> = withContext(Dispatchers.IO) {
        dao.pending(limit.coerceIn(1, MAX_BATCH))
    }

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) { dao.pendingCount() }

    /** Marks every row up to [ackedSeq] delivered. Never deletes unacked rows. */
    suspend fun acknowledge(ackedSeq: Long): Int = withContext(Dispatchers.IO) {
        dao.acknowledge(resolveDeviceId(), ackedSeq)
    }

    suspend fun recordFailure(seq: Long, message: String) = withContext(Dispatchers.IO) {
        dao.recordFailure(seq, message.take(MAX_ERROR_LENGTH))
    }

    /** Bounded housekeeping; only rows already acknowledged can disappear. */
    suspend fun prune(olderThanMillis: Long = SYNCED_RETENTION_MILLIS): Int =
        withContext(Dispatchers.IO) {
            dao.pruneSynced(clock() - olderThanMillis)
        }

    suspend fun snapshot(): OutboxSnapshot = withContext(Dispatchers.IO) {
        OutboxSnapshot(pending = dao.pendingCount(), newestSeq = dao.pendingMaxSeq())
    }

    /** Cached after the first call; a failing resolver must not poison the cache. */
    private suspend fun resolveDeviceId(): String = resolvedDeviceId.get() ?: try {
        deviceId().also { resolvedDeviceId.compareAndSet(null, it) }
    } catch (error: CancellationException) {
        throw error
    }

    companion object {
        /** `contracts/schemas/sync-request.schema.json` allows at most 100. */
        const val MAX_BATCH: Int = 100
        const val MAX_ERROR_LENGTH: Int = 200
        /** Unacked rows are never pruned; acknowledged ones are kept seven days. */
        const val SYNCED_RETENTION_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
    }
}
