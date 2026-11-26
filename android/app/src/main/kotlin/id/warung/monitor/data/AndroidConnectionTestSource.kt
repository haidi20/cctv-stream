package id.warung.monitor.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import id.warung.monitor.core.network.ConnectionTester
import id.warung.monitor.core.network.NetworkInspector
import id.warung.monitor.core.network.NetworkSnapshot
import id.warung.monitor.core.network.NetworkTransport
import id.warung.monitor.core.network.RtspEndpoint
import id.warung.monitor.core.network.RtspSessionProbe
import id.warung.monitor.core.network.SocketRtspSessionProbe
import id.warung.monitor.core.network.SocketTcpProbe
import id.warung.monitor.core.network.TcpProbe
import id.warung.monitor.feature.cameras.ui.ConnectionTestSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the active transport plus its IPv4 for the diagnostics sheet.
 *
 * Unlike the scan path this does not refuse cellular: the tester needs to name
 * the reason ("wrong network") instead of silently showing nothing.
 */
@Singleton
class AndroidNetworkInspector @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NetworkInspector {

    override suspend fun snapshot(): NetworkSnapshot {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkSnapshot(NetworkTransport.None, ipv4 = null)
        val active = connectivity.activeNetwork
            ?: return NetworkSnapshot(NetworkTransport.None, ipv4 = null)
        val capabilities: NetworkCapabilities =
            connectivity.getNetworkCapabilities(active)
                ?: return NetworkSnapshot(NetworkTransport.None, ipv4 = null)
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.Cellular
            else -> NetworkTransport.None
        }
        val ipv4 = connectivity.getLinkProperties(active)
            ?.linkAddresses
            ?.firstOrNull { address: LinkAddress -> address.address is java.net.Inet4Address }
            ?.address
            ?.hostAddress
        return NetworkSnapshot(transport, ipv4)
    }
}

/**
 * Real four-layer test: `ConnectivityManager` -> TCP -> RTSP DESCRIBE ->
 * first interleaved video packet.
 *
 * All socket work is confined to the probe implementations, which never log an
 * endpoint or a secret.
 */
@Singleton
class AndroidConnectionTestSource @Inject constructor(
    private val inspector: AndroidNetworkInspector,
) : ConnectionTestSource {

    private val tcp: TcpProbe = SocketTcpProbe()
    private val rtsp: RtspSessionProbe = SocketRtspSessionProbe()

    private val tester = ConnectionTester(
        network = inspector,
        tcp = tcp,
        rtsp = rtsp,
    )

    override suspend fun test(endpoint: RtspEndpoint) = tester.test(endpoint)
}