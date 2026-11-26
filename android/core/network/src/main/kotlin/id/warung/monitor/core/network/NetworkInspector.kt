package id.warung.monitor.core.network

/**
 * What the phone's network looks like right now.
 *
 * The connection test needs to distinguish "no link" from "cellular only":
 * both have no usable camera route, but only one of them is fixed by turning
 * Wi-Fi on.
 */
enum class NetworkTransport {
    Wifi,
    Cellular,
    None,
}

/** Snapshot used only for diagnostics; never persisted. */
data class NetworkSnapshot(
    val transport: NetworkTransport,
    val ipv4: String?,
)

fun interface NetworkInspector {
    suspend fun snapshot(): NetworkSnapshot
}

/**
 * One camera the user typed in, with a secret that must never be stored.
 *
 * [secret] is owned by the caller. Implementations may read it, must not keep a
 * reference, and must not put it in a message, exception, or log line.
 */
data class RtspEndpoint(
    val host: String,
    val port: Int,
    val path: String,
    val username: String? = null,
    val secret: CharArray? = null,
)

/** Outcome of the RTSP handshake, split into the two layers it covers. */
data class RtspProbeReport(
    val describe: LayerOutcome,
    val stream: LayerOutcome,
)

/**
 * Layer 3 (DESCRIBE with credentials) and layer 4 (first video packet).
 *
 * Implementations must never retry a 401: a wrong password repeated five times
 * locks commercial camera accounts.
 */
fun interface RtspSessionProbe {
    suspend fun handshake(
        endpoint: RtspEndpoint,
        describeTimeoutMillis: Int,
        streamTimeoutMillis: Int,
    ): RtspProbeReport
}