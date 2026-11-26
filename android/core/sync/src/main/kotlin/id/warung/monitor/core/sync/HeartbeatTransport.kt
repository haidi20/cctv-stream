package id.warung.monitor.core.sync

import id.warung.monitor.core.model.HeartbeatPayload

/**
 * What the heartbeat worker needs from the network.
 *
 * Separate from [SyncTransport] because the endpoints and failure handling
 * differ: a heartbeat is disposable (the next one is 30-60 s away), while a
 * sync batch carries durable events that must not be dropped.
 */
fun interface HeartbeatTransport {

    suspend fun send(payload: HeartbeatPayload): HeartbeatResult
}

/** Outcome of one `POST /v1/heartbeat`; no branch ever throws. */
sealed interface HeartbeatResult {
    data object Accepted : HeartbeatResult

    /** Retryable: offline, timeout, 5xx, or 429. */
    data class Retryable(val reason: String) : HeartbeatResult

    /** Permanent for this device until the user fixes credentials. */
    data class Rejected(val reason: String) : HeartbeatResult
}
