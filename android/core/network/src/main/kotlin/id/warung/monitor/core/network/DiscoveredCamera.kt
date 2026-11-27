package id.warung.monitor.core.network

import id.warung.monitor.core.model.CameraDeviceId

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
    /**
     * ONVIF endpoint reference (device UUID) when discovery supplied one.
     *
     * A stable identity survives a DHCP lease change: table maps keyed by
     * [CameraId] keep working when only the IP changes. Open-port probes have
     * no identity, so this stays null and callers fall back to [endpoint].
     */
    val deviceId: CameraDeviceId? = null,
) {
    val endpoint: String get() = "$host:$port"

    /**
     * Identity used for dedupe and "already saved" checks.
     *
     * ONVIF UUID wins over the endpoint; a camera that changed IP still
     * matches the saved profile. The UUID is normalised before it reaches
     * here, so equality is not case-sensitive.
     */
    val identity: String get() = deviceId
        ?.let { "uuid:${it.value}" }
        ?: "endpoint:$endpoint"
}
