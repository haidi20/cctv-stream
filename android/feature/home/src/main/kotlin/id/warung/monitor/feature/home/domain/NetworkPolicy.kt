package id.warung.monitor.feature.home.domain

/**
 * Decides whether opening an RTSP stream is allowed for a given reach state.
 *
 * Pure on purpose: the rule "never open RTSP on cellular" is the security and
 * product boundary (`network-topology.md` §7.3), so it must be testable without
 * Android or a socket. [StreamErrorKind] keeps the reason so the UI shows a
 * distinct message instead of a generic failure.
 */
object NetworkPolicy {

    /** `null` means "allowed"; otherwise the reason to show instead. */
    fun blockReason(reach: NetworkReach, sourceIsRtsp: Boolean): StreamErrorKind? {
        if (!sourceIsRtsp) return null
        return when (reach) {
            NetworkReach.WifiPrivate -> null
            NetworkReach.CellularOnly -> StreamErrorKind.WrongNetwork
            NetworkReach.Offline -> StreamErrorKind.NoNetwork
        }
    }

    /**
     * Whether a stream that failed on its own should retry once the transport
     * is back. A genuine auth failure must never retry: commercial cameras lock
     * the account after repeated 401s.
     */
    fun shouldRetryAfterNetworkReturns(lastFailure: StreamErrorKind?): Boolean =
        lastFailure?.isNetworkProblem == true

    /**
     * Whether a transport problem should replace the failure already on screen.
     *
     * Only `AuthFailed` is sticky: it needs a password fix, and showing
     * "Wi-Fi mati" over it would hide the real cause. Every other kind is
     * replaced, because while the phone is offline no socket can succeed.
     */
    fun shouldShowTransportProblem(lastFailure: StreamErrorKind?): Boolean =
        lastFailure != StreamErrorKind.AuthFailed
}