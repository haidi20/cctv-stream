package id.warung.monitor.sync

import id.warung.monitor.core.sync.SyncCoordinator
import id.warung.monitor.data.OutboxRepository

/**
 * The decision part of [SyncWorker], kept free of WorkManager so it is a plain
 * JVM unit test. It maps a coordinator [SyncCoordinator.Outcome] to
 * "retry after N ms", "run again now", or "give up until something changes".
 */
class SyncRunner(
    private val coordinator: SyncCoordinator,
    private val outbox: OutboxRepository,
    private val deviceId: suspend () -> String,
    private val attempts: SyncAttemptStore,
) {

    /** WorkManager caps its own backoff at 5 hours; our ladder tops at 15 min. */
    sealed interface Decision {
        data object Done : Decision
        data object RunAgain : Decision
        data class Retry(val delayMillis: Long) : Decision
        data class Stop(val reason: String) : Decision
    }

    /**
     * [SyncAttemptStore] is read *before* the coordinator computes a delay so
     * the pre-failure count selects the rung (0 -> 30 s). The counter is then
     * advanced only for retryable failures, because a new unique WorkManager
     * request resets `runAttemptCount` to zero.
     */
    suspend fun run(): Decision {
        val outcome = coordinator.syncAll(
            failureCount = attempts.failureCount(),
            deviceId = deviceId(),
        )
        return when (outcome) {
            is SyncCoordinator.Outcome.Empty -> {
                // Opportunistic housekeeping; never deletes unacked rows.
                runCatching { outbox.prune() }
                attempts.reset()
                Decision.Done
            }

            is SyncCoordinator.Outcome.Progress -> {
                // The server accepted data, so the transient streak is over.
                attempts.reset()
                Decision.RunAgain
            }

            is SyncCoordinator.Outcome.Retry -> {
                attempts.recordFailure()
                Decision.Retry(outcome.delayMillis)
            }

            is SyncCoordinator.Outcome.Blocked -> {
                // A rejection is not a transient failure: advancing the ladder
                // would only delay recovery after the user fixes credentials.
                attempts.reset()
                Decision.Stop(outcome.reason)
            }
        }
    }
}

