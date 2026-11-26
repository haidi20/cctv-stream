package id.warung.monitor.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the phone currently sits on the LAN.
 *
 * Returns the local IPv4 while connected to Wi-Fi, or `null` on cellular /
 * no network. The scan must be refused on cellular (AGENTS/network-topology
 * §7): probing someone's mobile carrier subnet is both useless and rude.
 */
fun interface LocalNetwork {
    suspend fun wifiIpv4(): String?
}

/** ONVIF WS-Discovery over UDP multicast; a fake is used in JVM tests. */
fun interface WsDiscoverySource {
    suspend fun discover(timeoutMillis: Int): List<DiscoveredCamera>
}

/** What the sheet should show after one scan attempt. */
sealed interface ScanOutcome {
    /** No Wi-Fi, or the address is not a private one: nothing was probed. */
    data object NoWifi : ScanOutcome

    data class Found(
        val cameras: List<ScoredCamera>,
        val fromCache: Boolean,
    ) : ScanOutcome
}

/**
 * Runs the two discovery layers in parallel and merges them.
 *
 * The 8 s budget is a product promise: after it, the sheet shows whatever was
 * found instead of a spinner. A WS-Discovery layer that hangs therefore cannot
 * starve the port probe, and vice versa.
 */
class CameraDiscovery(
    private val localNetwork: LocalNetwork,
    private val wsDiscovery: WsDiscoverySource,
    private val scanner: CameraPortScanner,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val budgetMillis: Long = DEFAULT_BUDGET_MILLIS,
    private val wsTimeoutMillis: Int = DEFAULT_WS_TIMEOUT_MILLIS,
) {

    private data class Cached(
        val onvif: List<DiscoveredCamera>,
        val openPorts: List<DiscoveredCamera>,
        val atMillis: Long,
    )

    private var cache: Cached? = null

    suspend fun scan(saved: List<DiscoveredCamera> = emptyList()): ScanOutcome {
        val cached = cache
        val now = clock()
        if (cached != null && now - cached.atMillis in 0 until cacheTtlMillis) {
            return ScanOutcome.Found(
                cameras = DiscoveryMerge.merge(saved, cached.onvif, cached.openPorts),
                fromCache = true,
            )
        }

        val ipv4 = localNetwork.wifiIpv4() ?: return ScanOutcome.NoWifi
        val hosts = SubnetScan.hostsOf(ipv4)
        if (hosts.isEmpty()) return ScanOutcome.NoWifi

        // Each layer gets its own deadline. A single outer deadline would be
        // wrong: when it fired, the layer that had already finished would be
        // discarded together with the one that hung.
        val (onvif, openPorts) = coroutineScope {
            val onvif = async {
                runCatching {
                    withTimeoutOrNull(wsTimeoutMillis.toLong()) {
                        wsDiscovery.discover(wsTimeoutMillis)
                    }
                }.getOrNull().orEmpty()
            }
            val ports = async {
                runCatching {
                    withTimeoutOrNull(budgetMillis) { scanner.scan(hosts) }
                }.getOrNull().orEmpty()
            }
            onvif.await() to ports.await()
        }
        cache = Cached(onvif = onvif, openPorts = openPorts, atMillis = now)
        return ScanOutcome.Found(
            cameras = DiscoveryMerge.merge(saved, onvif, openPorts),
            fromCache = false,
        )
    }

    fun invalidateCache() {
        cache = null
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MILLIS: Long = 10 * 60 * 1000L
        const val DEFAULT_BUDGET_MILLIS: Long = 8_000L
        const val DEFAULT_WS_TIMEOUT_MILLIS: Int = 3_000
    }
}