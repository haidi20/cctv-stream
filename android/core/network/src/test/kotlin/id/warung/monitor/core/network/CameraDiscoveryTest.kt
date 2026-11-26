package id.warung.monitor.core.network

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDiscoveryTest {

    private val openPort = DiscoveredCamera("192.168.1.5", 554)
    private val onvifCam = DiscoveredCamera("192.168.1.6", 554, brand = "EZVIZ")

    private fun camera(
        localIp: String? = "192.168.1.20",
        onvif: suspend () -> List<DiscoveredCamera> = { emptyList() },
        probe: TcpProbe = TcpProbe { host, _, _ -> host == "192.168.1.5" },
        clock: () -> Long = { 1_000L },
    ) = CameraDiscovery(
        localNetwork = LocalNetwork { localIp },
        wsDiscovery = WsDiscoverySource { _ -> onvif() },
        scanner = CameraPortScanner(probe, ports = listOf(554)),
        clock = clock,
    )

    @Test
    fun cellular_means_no_scan_and_no_probe_is_attempted() = runTest {
        var probed = false
        val discovery = camera(localIp = null, probe = TcpProbe { _, _, _ -> probed = true; true })

        assertEquals(ScanOutcome.NoWifi, discovery.scan())
        assertFalse("a cellular network must never be probed", probed)
    }

    @Test
    fun both_layers_are_merged_into_one_ranked_list() = runTest {
        val outcome = camera(onvif = { listOf(onvifCam) }).scan() as ScanOutcome.Found

        assertEquals(
            listOf("192.168.1.6:554", "192.168.1.5:554"),
            outcome.cameras.map { it.camera.endpoint },
        )
        assertEquals(DiscoverySource.Onvif, outcome.cameras.first().source)
        assertFalse(outcome.fromCache)
    }

    @Test
    fun a_saved_camera_outranks_discovery_results() = runTest {
        val outcome = camera(onvif = { listOf(onvifCam) })
            .scan(saved = listOf(DiscoveredCamera("192.168.1.99", 554))) as ScanOutcome.Found

        assertEquals(DiscoverySource.Saved, outcome.cameras.first().source)
    }

    @Test
    fun a_hanging_ws_discovery_layer_does_not_starve_the_port_probe() = runTest {
        val discovery = CameraDiscovery(
            localNetwork = LocalNetwork { "192.168.1.20" },
            wsDiscovery = WsDiscoverySource { awaitCancellation() },
            scanner = CameraPortScanner(TcpProbe { host, _, _ -> host == "192.168.1.5" }, ports = listOf(554)),
            budgetMillis = 1_000L,
        )

        val outcome = discovery.scan() as ScanOutcome.Found

        assertEquals(listOf("192.168.1.5:554"), outcome.cameras.map { it.camera.endpoint })
    }

    @Test
    fun a_failing_discovery_layer_is_treated_as_no_results() = runTest {
        val discovery = CameraDiscovery(
            localNetwork = LocalNetwork { "192.168.1.20" },
            wsDiscovery = WsDiscoverySource { _ -> error("multicast blocked") },
            scanner = CameraPortScanner(TcpProbe { _, _, _ -> false }, ports = listOf(554)),
        )

        val outcome = discovery.scan() as ScanOutcome.Found

        assertTrue(outcome.cameras.isEmpty())
    }

    @Test
    fun a_second_scan_inside_the_ttl_reuses_the_cache() = runTest {
        var clock = 1_000L
        var probes = 0
        val discovery = camera(
            probe = TcpProbe { _, _, _ -> probes++; true },
            clock = { clock },
        )

        discovery.scan()
        val afterFirst = probes
        clock += 60_000L
        val second = discovery.scan() as ScanOutcome.Found

        assertEquals(afterFirst, probes)
        assertTrue(second.fromCache)
    }

    @Test
    fun the_cache_expires_and_a_new_scan_runs_again() = runTest {
        var clock = 1_000L
        var probes = 0
        val discovery = CameraDiscovery(
            localNetwork = LocalNetwork { "192.168.1.20" },
            wsDiscovery = WsDiscoverySource { _ -> emptyList() },
            scanner = CameraPortScanner(TcpProbe { _, _, _ -> probes++; true }, ports = listOf(554)),
            clock = { clock },
            cacheTtlMillis = 10_000L,
        )

        discovery.scan()
        val first = probes
        clock += 10_001L
        val second = discovery.scan() as ScanOutcome.Found

        assertTrue(probes > first)
        assertFalse(second.fromCache)
    }

    @Test
    fun invalidate_cache_forces_the_next_scan_to_be_fresh() = runTest {
        var scans = 0
        val discovery = camera(
            probe = TcpProbe { _, _, _ -> scans++; true },
        )

        discovery.scan()
        val afterFirstScan = scans
        discovery.invalidateCache()
        val outcome = discovery.scan() as ScanOutcome.Found

        // A /24 with two ports is 506 probes; a cache hit would add none.
        assertTrue("expected another full scan, got ${scans - afterFirstScan} probes", scans > afterFirstScan)
        assertFalse(outcome.fromCache)
    }
}