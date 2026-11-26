package id.warung.monitor.data

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import id.warung.monitor.core.network.DiscoveredCamera
import id.warung.monitor.core.network.WsDiscovery
import id.warung.monitor.core.network.WsDiscoverySource
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ONVIF WS-Discovery over UDP multicast.
 *
 * Best-effort only: many consumer cameras (including EZVIZ) have discovery
 * disabled, so an empty list is a normal outcome, never an error. Sending a
 * Probe does not authenticate and contains no credentials.
 */
class UdpWsDiscoverySource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WsDiscoverySource {

    override suspend fun discover(timeoutMillis: Int): List<DiscoveredCamera> =
        withContext(Dispatchers.IO) {
            val lock = multicastLock()
            lock?.acquire()
            try {
                withTimeoutOrNull(timeoutMillis.toLong()) { collect(timeoutMillis) }.orEmpty()
            } finally {
                // Holding the lock past the scan keeps the radio awake for no
                // reason; release even when the coroutine is cancelled.
                lock?.let { runCatching { it.release() } }
            }
        }

    /**
     * Wi-Fi multicast is filtered unless the app opts in. Failure to obtain the
     * lock is not fatal: the port probe still answers, so the scan degrades to
     * "no ONVIF rows" instead of failing.
     */
    private fun multicastLock(): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply { setReferenceCounted(false) }
    }.getOrNull()

    private fun collect(timeoutMillis: Int): List<DiscoveredCamera> {
        val found = LinkedHashMap<String, DiscoveredCamera>()
        DatagramSocket().use { socket ->
            socket.soTimeout = SOCKET_READ_TIMEOUT_MILLIS
            val probe = probeMessage().toByteArray(Charsets.UTF_8)
            socket.send(
                DatagramPacket(probe, probe.size, InetSocketAddress(InetAddress.getByName(GROUP), PORT)),
            )
            val buffer = ByteArray(MAX_DATAGRAM_BYTES)
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val xml = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                WsDiscovery.parseProbeMatch(xml).forEach { camera ->
                    found.putIfAbsent(camera.endpoint, camera)
                }
            }
        }
        return found.values.toList()
    }

    private fun probeMessage(): String {
        val messageId = "uuid:" + UUID.randomUUID()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
              <e:Header>
                <w:MessageID>$messageId</w:MessageID>
                <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
                <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
              </e:Header>
              <e:Body>
                <d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe>
              </e:Body>
            </e:Envelope>
        """.trimIndent()
    }

    companion object {
        const val GROUP: String = "239.255.255.250"
        private const val MULTICAST_LOCK_TAG = "cctv-stream-ws-discovery"
        const val PORT: Int = 3702
        private const val MAX_DATAGRAM_BYTES = 8 * 1024
        private const val SOCKET_READ_TIMEOUT_MILLIS = 200
    }
}