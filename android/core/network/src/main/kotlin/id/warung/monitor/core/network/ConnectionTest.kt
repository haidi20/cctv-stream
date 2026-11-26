package id.warung.monitor.core.network

/** The four ordered checks behind the "Tes koneksi" button. */
enum class ConnectionLayer {
    /** Wi-Fi transport and a private IPv4 address. */
    Network,

    /** TCP connect to the camera port. */
    Reachability,

    /** RTSP DESCRIBE with the typed credentials. */
    Credentials,

    /** SETUP + PLAY + one interleaved video packet. */
    Stream,
}

/**
 * Stable failure reasons.
 *
 * The enum intentionally carries no host, IP, username, or password: a UI
 * message built from it cannot leak an endpoint, and no log line can either.
 */
enum class ConnectionFailure {
    NoNetwork,
    WrongNetwork,
    Unreachable,
    AuthFailed,
    Protocol,
    Timeout,
    Closed,
    Unknown,
}

/** Result of one of the four ordered layers. */
sealed interface LayerOutcome {
    data object Passed : LayerOutcome

    data class Failed(val failure: ConnectionFailure) : LayerOutcome

    /** An earlier layer failed, so this layer was never attempted. */
    data object NotRun : LayerOutcome
}

/** Immutable snapshot of a manual connection test. */
data class ConnectionReport(
    val layers: Map<ConnectionLayer, LayerOutcome>,
) {
    /** First failure in layer order, or `null` when everything passed. */
    val failure: ConnectionFailure?
        get() = ConnectionLayer.entries.firstNotNullOfOrNull { layer ->
            (layers[layer] as? LayerOutcome.Failed)?.failure
        }

    val passed: Boolean
        get() = ConnectionLayer.entries.all { layers[it] == LayerOutcome.Passed }

    companion object {
        val NotStarted: ConnectionReport = ConnectionReport(
            ConnectionLayer.entries.associateWith { LayerOutcome.NotRun },
        )
    }
}