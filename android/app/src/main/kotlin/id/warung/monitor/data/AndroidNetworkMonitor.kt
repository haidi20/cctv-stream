package id.warung.monitor.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import id.warung.monitor.core.network.SubnetScan
import id.warung.monitor.feature.home.domain.NetworkMonitor
import id.warung.monitor.feature.home.domain.NetworkReach
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Emits the transport class Home needs to decide `no_network`/`wrong_network`.
 *
 * Only Wi-Fi with a private IPv4 counts as usable: a public Wi-Fi address (a
 * captive portal or a hotel network) is as useless as cellular for a local
 * camera. The callback is unregistered when the collector stops.
 */
@Singleton
class AndroidNetworkMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NetworkMonitor {

    override fun reach(): Flow<NetworkReach> = callbackFlow {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            trySend(NetworkReach.Offline)
            awaitClose()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(read(connectivity))
            }

            override fun onLost(network: Network) {
                trySend(read(connectivity))
            }

            override fun onAvailable(network: Network) {
                trySend(read(connectivity))
            }
        }

        trySend(read(connectivity))
        // No NET_CAPABILITY_INTERNET filter: a warung LAN camera network may
        // have no internet route, and requiring it would report Offline on a
        // perfectly working local Wi-Fi.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        // Best effort: on some OEM builds registering while the app is in the
        // background throws; the current snapshot is still emitted.
        runCatching { connectivity.registerNetworkCallback(request, callback) }
        awaitClose {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    private fun read(connectivity: ConnectivityManager): NetworkReach {
        val active = connectivity.activeNetwork ?: return NetworkReach.Offline
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return NetworkReach.Offline
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                NetworkReach.CellularOnly
            } else {
                NetworkReach.Offline
            }
        }
        val ipv4 = connectivity.getLinkProperties(active)
            ?.linkAddresses
            ?.firstOrNull { address: LinkAddress -> address.address is java.net.Inet4Address }
            ?.address
            ?.hostAddress
        return if (ipv4 != null && SubnetScan.isPrivate(ipv4)) {
            NetworkReach.WifiPrivate
        } else {
            NetworkReach.Offline
        }
    }
}