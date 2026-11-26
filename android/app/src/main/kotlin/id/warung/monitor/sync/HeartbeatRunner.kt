package id.warung.monitor.sync

import id.warung.monitor.core.sync.HeartbeatResult
import id.warung.monitor.core.sync.HeartbeatTransport
import id.warung.monitor.core.model.HeartbeatQueue
import id.warung.monitor.core.sync.SyncSettings
import id.warung.monitor.data.OutboxRepository
import id.warung.monitor.feature.home.domain.EnginePhase
import id.warung.monitor.feature.home.domain.EngineStatusSource
import kotlinx.coroutines.CancellationException

/**
 * One heartbeat attempt, kept free of Android so it is a plain JVM test.
 *
 * A heartbeat is disposable: it is never queued, and a failure only changes
 * when the next attempt is scheduled. The payload is built from the same
 * [EngineStatusSource] the Home screen reads, so a dead stream cannot be
 * reported as healthy.
 */
class HeartbeatRunner(
    private val statusSource: EngineStatusSource,
    private val facts: suspend () -> HeartbeatDeviceFacts,
    private val transport: HeartbeatTransport,
    private val settings: SyncSettings,
    private val outbox: OutboxRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface Decision {
        /** Sent; the next attempt follows the live cadence (30 s). */
        data object SentLive : Decision

        /** Sent; the next attempt follows the idle cadence (60 s). */
        data object SentIdle : Decision

        /** No base URL configured; stop the chain rather than poll forever. */
        data object NotConfigured : Decision

        /** Transport failed; try again on the idle cadence. */
        data class Retry(val delayMillis: Long) : Decision

        /** Credentials or payload rejected; back off hard until the user acts. */
        data class Stop(val reason: String) : Decision
    }

    suspend fun run(): Decision {
        if (settings.endpoint() == null) return Decision.NotConfigured
        val status = statusSource.read(null)
        val currentFacts = try {
            facts()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return Decision.Retry(HeartbeatScheduler.IDLE_INTERVAL_MILLIS)
        }
        val queue = readQueue()
        val payload = HeartbeatBuilder.build(
            status = status,
            facts = currentFacts.copy(queue = currentFacts.queue ?: queue),
            nowMillis = clock(),
        )
        return when (val result = transport.send(payload)) {
            is HeartbeatResult.Accepted -> {
                if (status.phase == EnginePhase.Live) {
                    Decision.SentLive
                } else {
                    Decision.SentIdle
                }
            }

            is HeartbeatResult.Retryable -> Decision.Retry(HeartbeatScheduler.IDLE_INTERVAL_MILLIS)
            is HeartbeatResult.Rejected -> Decision.Stop(result.reason)
        }
    }

    /**
     * Outbox depth is optional: without a repository this field is omitted,
     * which is different from reporting zero pending rows.
     */
    private suspend fun readQueue(): HeartbeatQueue? {
        val repository = outbox ?: return null
        val snapshot = try {
            repository.snapshot()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return null
        }
        return HeartbeatQueue(pending = snapshot.pending.toLong())
    }
}
