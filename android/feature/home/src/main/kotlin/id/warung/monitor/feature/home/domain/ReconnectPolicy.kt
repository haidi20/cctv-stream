package id.warung.monitor.feature.home.domain

/**
 * Decides whether a failed stream may be retried, and how long to wait.
 *
 * Only the transports that can heal on their own are retried. `AuthFailed`
 * and `Protocol` stay failed on purpose: retrying a 401 can lock a camera
 * account, and a codec the device cannot decode will fail identically every
 * time. `NoNetwork`/`WrongNetwork` are not retried on a timer at all; they
 * wait for [NetworkMonitor] to report local Wi-Fi again.
 */
object ReconnectPolicy {

    /** Attempts after the initial connection: the 1-2-4-8-16 ladder from the plan. */
    const val MAX_ATTEMPTS: Int = 5

    private val DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)

    fun isRetryable(kind: StreamErrorKind): Boolean = when (kind) {
        StreamErrorKind.Unreachable,
        StreamErrorKind.Timeout,
        StreamErrorKind.Closed,
        -> true

        StreamErrorKind.AuthFailed,
        StreamErrorKind.Protocol,
        StreamErrorKind.NoNetwork,
        StreamErrorKind.WrongNetwork,
        StreamErrorKind.Unknown,
        -> false
    }

    /**
     * Whether the engine should be told that the video died.
     *
     * `NoNetwork`/`WrongNetwork` never reached the camera, so there is no
     * stream to tear down. Every other failure means the stream was open (or
     * tried to be), and reporting it keeps the engine's table statuses
     * honest. A caller whose engine is stopped still gets `NotRunning`, which
     * is ignored.
     */
    fun isReportable(kind: StreamErrorKind): Boolean = when (kind) {
        StreamErrorKind.NoNetwork,
        StreamErrorKind.WrongNetwork,
        -> false

        StreamErrorKind.Unreachable,
        StreamErrorKind.Timeout,
        StreamErrorKind.AuthFailed,
        StreamErrorKind.Protocol,
        StreamErrorKind.Closed,
        StreamErrorKind.Unknown,
        -> true
    }

    /**
     * 1-based attempt index. Beyond [MAX_ATTEMPTS] the last delay is repeated
     * so callers never index past the list.
     */
    fun delayMillis(attempt: Int): Long {
        require(attempt >= 1) { "Reconnect attempt is 1-based" }
        return DELAYS_MILLIS[minOf(attempt, DELAYS_MILLIS.size) - 1]
    }
}