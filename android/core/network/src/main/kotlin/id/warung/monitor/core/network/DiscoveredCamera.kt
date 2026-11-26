package id.warung.monitor.core.network

/**
 * A camera endpoint discovered on the local network.
 *
 * Deliberately holds no credentials: discovery only proves "something answers
 * RTSP here". The password is collected later and kept in the Keystore.
 */
data class DiscoveredCamera(
    val host: String,
    val port: Int,
    val brand: String? = null,
) {
    val endpoint: String get() = "$host:$port"
}