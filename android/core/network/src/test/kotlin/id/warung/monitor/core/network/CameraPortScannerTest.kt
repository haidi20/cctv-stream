package id.warung.monitor.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPortScannerTest {

    @Test
    fun only_hosts_that_answer_on_a_probe_port_are_reported() = runTest {
        val open = setOf("192.168.1.5:554", "192.168.1.9:8554")
        val probe = TcpProbe { host, port, _ -> "$host:$port" in open }
        val scanner = CameraPortScanner(probe)

        val result = scanner.scan(listOf("192.168.1.5", "192.168.1.6", "192.168.1.9"))

        assertEquals(
            listOf("192.168.1.5:554", "192.168.1.9:8554"),
            result.map { it.endpoint },
        )
    }

    @Test
    fun results_are_sorted_numerically_not_lexicographically() = runTest {
        val probe = TcpProbe { host, port, _ -> port == 554 }
        val scanner = CameraPortScanner(probe)

        val result = scanner.scan(listOf("192.168.1.10", "192.168.1.9", "192.168.1.100"))

        assertEquals(
            listOf("192.168.1.9", "192.168.1.10", "192.168.1.100"),
            result.map { it.host },
        )
    }

    @Test
    fun a_failing_probe_is_treated_as_closed_not_as_a_crash() = runTest {
        val probe = TcpProbe { host, _, _ ->
            if (host == "192.168.1.5") error("connection reset") else true
        }
        val scanner = CameraPortScanner(
            probe = RecoveringProbe(probe),
            ports = listOf(554),
        )

        val result = scanner.scan(listOf("192.168.1.5", "192.168.1.6"))

        assertEquals(listOf("192.168.1.6:554"), result.map { it.endpoint })
    }

    @Test
    fun an_empty_host_list_returns_nothing_without_probing() = runTest {
        var calls = 0
        val scanner = CameraPortScanner(TcpProbe { _, _, _ -> calls++; true })

        assertTrue(scanner.scan(emptyList()).isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun concurrency_limit_is_respected() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        val probe = TcpProbe { _, _, _ ->
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            // Force interleaving; without a suspension point the test dispatcher
            // would run each probe to completion and the bound would be untested.
            kotlinx.coroutines.yield()
            inFlight--
            true
        }
        val scanner = CameraPortScanner(probe, ports = listOf(554), concurrency = 4)

        scanner.scan((1..20).map { "192.168.1.$it" })

        assertTrue("max in flight was $maxInFlight", maxInFlight <= 4)
    }

    /** Mirrors what SocketTcpProbe does: an exception means "closed". */
    private class RecoveringProbe(private val delegate: TcpProbe) : TcpProbe {
        override suspend fun isOpen(host: String, port: Int, timeoutMillis: Int): Boolean =
            runCatching { delegate.isOpen(host, port, timeoutMillis) }.getOrDefault(false)
    }
}