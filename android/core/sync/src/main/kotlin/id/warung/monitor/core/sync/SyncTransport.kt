package id.warung.monitor.core.sync

import id.warung.monitor.core.model.SyncAck
import id.warung.monitor.core.model.SyncRequestEnvelope

/**
 * What the sync coordinator needs from the network.
 *
 * Kept as an interface so the worker tests never open a socket and so Ktor
 * stays an `:app`-level detail. Implementations must not throw for HTTP
 * failures; they map them to [SyncResult].
 */
fun interface SyncTransport {

    suspend fun send(envelope: SyncRequestEnvelope): SyncResult
}

/** Outcome of one `POST /v1/sync`; every branch is actionable offline. */
sealed interface SyncResult {

    /** The server committed the batch and acknowledged up to [ack]. */
    data class Acked(val ack: SyncAck) : SyncResult

    /**
     * The server detected a gap and expects [expectedSeq]. The caller replays
     * from that point instead of dropping rows.
     */
    data class SequenceGap(val expectedSeq: Long) : SyncResult

    /** The batch is too large; callers must split, never resend as-is. */
    data object PayloadTooLarge : SyncResult

    /** Retryable: offline, timeout, 5xx, or 429. */
    data class Retryable(val reason: String) : SyncResult

    /** Permanent for this payload: 400/401/403. Retrying cannot help. */
    data class Rejected(val reason: String) : SyncResult
}

