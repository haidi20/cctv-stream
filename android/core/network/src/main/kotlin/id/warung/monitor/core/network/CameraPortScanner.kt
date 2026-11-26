package id.warung.monitor.core.network

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The only platform seam in this module: opening one TCP connection.
 *
 * Everything above it (subnet math, concurrency, dedupe, ranking) is pure and
 * tested on the JVM. Implementations must never send credentials.
 */
fun interface TcpProbe {
    suspend fun isOpen(host: String, port: Int, timeoutMillis: Int): Boolean
}

/** Real connect. Uses `connect` + immediate close, no RTSP bytes are sent. */
class SocketTcpProbe(
    private val connect: (String, Int, Int) -> Boolean = ::connectOnce,
) : TcpProbe {

    override suspend fun isOpen(host: String, port: Int, timeoutMillis: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { connect(host, port, timeoutMillis) }.getOrDefault(false)
        }

    companion object {
        fun connectOnce(host: String, port: Int, timeoutMillis: Int): Boolean {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                return socket.isConnected
            }
        }
    }
}

/**
 * Probes the given hosts for RTSP ports with a bounded fan-out.
 *
 * 254 hosts / 32 in flight / 400 ms each is roughly 3.2 s worst case, which is
 * why the UI can promise a result within its 8 s budget.
 */
class CameraPortScanner(
    private val probe: TcpProbe,
    private val ports: List<Int> = DEFAULT_PORTS,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) {

    init {
        require(concurrency > 0) { "concurrency must be positive" }
        require(timeoutMillis > 0) { "timeout must be positive" }
        require(ports.isNotEmpty()) { "at least one port is required" }
    }

    suspend fun scan(hosts: List<String>): List<DiscoveredCamera> = coroutineScope {
        if (hosts.isEmpty()) return@coroutineScope emptyList()
        val permits = Semaphore(concurrency)
        hosts
            .flatMap { host ->
                ports.map { port ->
                    async {
                        permits.withPermit {
                            if (probe.isOpen(host, port, timeoutMillis)) {
                                DiscoveredCamera(host = host, port = port)
                            } else {
                                null
                            }
                        }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
            .sortedWith(compareBy({ it.host.lastSegment() }, { it.port }))
    }

    private fun String.lastSegment(): Int = substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE

    companion object {
        val DEFAULT_PORTS: List<Int> = listOf(554, 8554)
        const val DEFAULT_CONCURRENCY: Int = 32
        const val DEFAULT_TIMEOUT_MILLIS: Int = 400
    }
}