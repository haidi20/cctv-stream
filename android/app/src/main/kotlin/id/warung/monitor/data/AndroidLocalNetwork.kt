package id.warung.monitor.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import id.warung.monitor.core.network.LocalNetwork
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the phone's IPv4 only while Wi-Fi is the active transport.
 *
 * Returning `null` on cellular is the security boundary: the scan then refuses
 * to run instead of probing a carrier subnet.
 */
@Singleton
class AndroidLocalNetwork @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocalNetwork {

    override suspend fun wifiIpv4(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val active: Network = connectivity.activeNetwork ?: return null
        val capabilities: NetworkCapabilities = connectivity.getNetworkCapabilities(active) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val properties: LinkProperties = connectivity.getLinkProperties(active) ?: return null
        return properties.linkAddresses.firstOrNull { address -> address.address is java.net.Inet4Address }
            ?.let(LinkAddress::getAddress)
            ?.hostAddress
    }
}