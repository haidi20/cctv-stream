package id.warung.monitor.core.network

/**
 * Runs the four ordered checks behind "Tes koneksi".
 *
 * Ordering is the product requirement (`android-app-plan.md` §5.7): a user on
 * the wrong Wi-Fi must never be told their password is wrong. Execution stops at
 * the first failure and later layers are reported as [LayerOutcome.NotRun].
 *
 * No layer formats a message. The UI maps [ConnectionFailure] to Indonesian
 * text, so no endpoint or credential can reach a log by accident.
 */
class ConnectionTester(
    private val network: NetworkInspector,
    private val tcp: TcpProbe,
    private val rtsp: RtspSessionProbe,
    private val reachabilityTimeoutMillis: Int = DEFAULT_REACHABILITY_TIMEOUT_MILLIS,
    private val describeTimeoutMillis: Int = DEFAULT_DESCRIBE_TIMEOUT_MILLIS,
    private val streamTimeoutMillis: Int = DEFAULT_STREAM_TIMEOUT_MILLIS,
) {

    init {
        require(reachabilityTimeoutMillis > 0) { "reachability timeout must be positive" }
        require(describeTimeoutMillis > 0) { "describe timeout must be positive" }
        require(streamTimeoutMillis > 0) { "stream timeout must be positive" }
    }

    suspend fun test(endpoint: RtspEndpoint): ConnectionReport {
        val outcomes = LinkedHashMap<ConnectionLayer, LayerOutcome>()

        val networkOutcome = try {
            networkLayer()
        } catch (error: Throwable) {
            // A broken ConnectivityManager must not crash the sheet; treat it as
            // "no usable link" rather than guessing that the camera is at fault.
            LayerOutcome.Failed(ConnectionFailure.NoNetwork)
        }
        outcomes[ConnectionLayer.Network] = networkOutcome
        if (networkOutcome is LayerOutcome.Failed) {
            // Keep the specific reason: "cellular only" is fixed by turning on
            // Wi-Fi, while "no link" is a different instruction for the user.
            return reportWith(outcomes, ConnectionLayer.Network, networkOutcome.failure)
        }

        val reachable = try {
            tcp.isOpen(endpoint.host, endpoint.port, reachabilityTimeoutMillis)
        } catch (error: Throwable) {
            false
        }
        val reachabilityOutcome = if (reachable) {
            LayerOutcome.Passed
        } else {
            LayerOutcome.Failed(ConnectionFailure.Unreachable)
        }
        outcomes[ConnectionLayer.Reachability] = reachabilityOutcome
        if (reachabilityOutcome is LayerOutcome.Failed) {
            return reportWith(outcomes, ConnectionLayer.Reachability, ConnectionFailure.Unreachable)
        }

        val probe = try {
            rtsp.handshake(endpoint, describeTimeoutMillis, streamTimeoutMillis)
        } catch (error: Throwable) {
            // An unexpected exception while probing maps to `protocol`: the
            // camera answered TCP but the handshake could not be completed.
            RtspProbeReport(
                describe = LayerOutcome.Failed(ConnectionFailure.Protocol),
                stream = LayerOutcome.NotRun,
            )
        }
        outcomes[ConnectionLayer.Credentials] = probe.describe
        if (probe.describe is LayerOutcome.Failed) {
            return reportWith(outcomes, ConnectionLayer.Credentials, probe.describe.failure)
        }

        outcomes[ConnectionLayer.Stream] = probe.stream
        return ConnectionReport(outcomes.toMap())
    }

    private suspend fun networkLayer(): LayerOutcome {
        val snapshot = network.snapshot()
        val privateIpv4 = snapshot.ipv4?.takeIf(SubnetScan::isPrivate)
        return when {
            snapshot.transport == NetworkTransport.Wifi && privateIpv4 != null -> LayerOutcome.Passed
            snapshot.transport == NetworkTransport.Wifi -> LayerOutcome.Failed(ConnectionFailure.WrongNetwork)
            snapshot.transport == NetworkTransport.Cellular -> LayerOutcome.Failed(ConnectionFailure.WrongNetwork)
            else -> LayerOutcome.Failed(ConnectionFailure.NoNetwork)
        }
    }

    /**
     * Fills the remaining layers as [LayerOutcome.NotRun], keeping the ones that
     * already ran. The failing layer is written explicitly so a caller can never
     * receive a `NotRun` where a failure happened.
     */
    private fun reportWith(
        outcomes: Map<ConnectionLayer, LayerOutcome>,
        failedLayer: ConnectionLayer,
        failure: ConnectionFailure,
    ): ConnectionReport {
        val complete = LinkedHashMap<ConnectionLayer, LayerOutcome>()
        for (layer in ConnectionLayer.entries) {
            complete[layer] = outcomes[layer] ?: LayerOutcome.NotRun
        }
        complete[failedLayer] = LayerOutcome.Failed(failure)
        return ConnectionReport(complete)
    }

    companion object {
        const val DEFAULT_REACHABILITY_TIMEOUT_MILLIS: Int = 1_500
        const val DEFAULT_DESCRIBE_TIMEOUT_MILLIS: Int = 3_000
        const val DEFAULT_STREAM_TIMEOUT_MILLIS: Int = 5_000
    }
}