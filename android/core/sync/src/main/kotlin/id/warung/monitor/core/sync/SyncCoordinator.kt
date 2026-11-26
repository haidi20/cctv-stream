package id.warung.monitor.core.sync

import id.warung.monitor.core.model.SyncAck
import id.warung.monitor.core.model.SyncRequestEnvelope
import kotlinx.coroutines.CancellationException

/**
 * One sync attempt over the pending outbox.
 *
 * The coordinator owns the ordering rules:
 * - a batch is only removed after an ACK (`acked_seq`),
 * - a `409` replays from `expected_seq` instead of skipping,
 * - a `413` halves the batch,
 * - a permanent rejection records the failure but keeps the rows,
 * - offline/5xx surfaces a retry delay from [SyncBackoff].
 *
 * It never talks to Room directly; [Outbox] is the seam so tests use a list.
 */
class SyncCoordinator(
    private val outbox: Outbox,
    private val transport: SyncTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The slice of `OutboxRepository` the coordinator needs. */
    interface Outbox {
        suspend fun pending(limit: Int): List<PendingEvent>
        suspend fun acknowledge(ackedSeq: Long): Int
        suspend fun recordFailure(seq: Long, message: String)
    }

    data class PendingEvent(val seq: Long, val payload: String)

    /** Result of one full drain; a worker turns this into `Result.retry()`. */
    sealed interface Outcome {
        /** No pending rows; nothing to schedule. */
        data object Empty : Outcome

        /** At least one batch was acknowledged. [acked] counts rows removed. */
        data class Progress(val acked: Int, val batches: Int) : Outcome

        /** Stop and retry later; [delayMillis] comes from [SyncBackoff]. */
        data class Retry(val delayMillis: Long, val reason: String) : Outcome

        /** The server rejected the payload; rows stay pending for a human. */
        data class Blocked(val reason: String) : Outcome
    }

    /**
     * Drains as many full batches as the server accepts.
     *
     * Stops on the first retry/blocked result so one broken batch cannot spin
     * through the whole queue. `failureCount` is supplied by the caller because
     * the retry ladder must survive new WorkManager requests (which reset
     * `runAttemptCount`), so it lives in a persisted store on the app side.
     */
    suspend fun syncAll(
        failureCount: Int = 0,
        deviceId: String,
        maxBatches: Int = DEFAULT_MAX_BATCHES,
    ): Outcome {
        var acked = 0
        var batches = 0
        var limit = BATCH_SIZE
        var replays = 0
        var lastAckedSeq = 0L

        while (batches < maxBatches) {
            val rows = outbox.pending(limit)
            if (rows.isEmpty()) {
                return if (acked == 0) Outcome.Empty else Outcome.Progress(acked, batches)
            }

            val envelope = SyncRequestEnvelope(
                deviceId = deviceId,
                seq = rows.last().seq,
                sentAtEpochMs = clock(),
                events = rows.map { it.payload },
            )

            val result = try {
                transport.send(envelope)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // A transport that throws is a network failure, not a data bug.
                SyncResult.Retryable("transport_error")
            }

            when (result) {
                is SyncResult.Acked -> {
                    val ackSeq = result.ack.ackedSeq
                    val affected = outbox.acknowledge(ackSeq)
                    acked += affected
                    batches += 1
                    if (affected == 0) {
                        // Either a concurrent worker already ACKed these rows
                        // or the server ACKed outside the batch. Re-reading
                        // resolves the first case; an empty queue ends the run.
                        val remaining = outbox.pending(limit)
                        if (remaining.isEmpty()) {
                            return Outcome.Progress(acked, batches)
                        }
                        // Rows the server claims to have ACKed are still
                        // pending locally, or the sequence did not advance.
                        // Retrying cannot fix either without a snapshot.
                        if (ackSeq <= lastAckedSeq) {
                            return Outcome.Blocked("ack_not_advancing")
                        }
                        return Outcome.Blocked("ack_not_applied")
                    }
                    lastAckedSeq = ackSeq
                }

                is SyncResult.SequenceGap -> {
                    // Our oldest pending row is what the server can accept next.
                    // Equal means the previous request was stored but its ACK
                    // was lost: resend once. Greater means rows we never sent
                    // are missing (server reset), which only a snapshot fixes.
                    val oldest = outbox.pending(1).firstOrNull()
                    if (oldest == null) return Outcome.Progress(acked, batches)
                    if (oldest.seq > result.expectedSeq) {
                        return Outcome.Blocked("seq_gap_needs_snapshot")
                    }
                    if (oldest.seq < result.expectedSeq) {
                        return Outcome.Blocked("seq_gap_ahead_of_local")
                    }
                    if (replays >= MAX_REPLAYS) {
                        outbox.recordFailure(oldest.seq, "seq_gap_replay_exhausted")
                        return Outcome.Retry(
                            delayMillis = SyncBackoff.delayFor(failureCount).inWholeMilliseconds,
                            reason = "seq_gap_replay_exhausted",
                        )
                    }
                    replays += 1
                    batches += 1
                }

                is SyncResult.PayloadTooLarge -> {
                    if (limit <= 1) return Outcome.Blocked("payload_too_large_single_event")
                    limit = (limit / 2).coerceAtLeast(1)
                }

                is SyncResult.Retryable -> {
                    outbox.recordFailure(rows.first().seq, result.reason)
                    return Outcome.Retry(SyncBackoff.delayFor(failureCount).inWholeMilliseconds, result.reason)
                }

                is SyncResult.Rejected -> {
                    outbox.recordFailure(rows.first().seq, result.reason)
                    return Outcome.Blocked(result.reason)
                }
            }
        }

        return Outcome.Progress(acked, batches)
    }

    companion object {
        /** Mirrors `SyncRequestEnvelope.MAX_EVENTS`. */
        const val BATCH_SIZE: Int = 100

        /** A single worker run must be bounded even if every batch succeeds. */
        const val DEFAULT_MAX_BATCHES: Int = 20

        /** One resend after a lost ACK; a second would just burn the budget. */
        const val MAX_REPLAYS: Int = 1
    }
}

