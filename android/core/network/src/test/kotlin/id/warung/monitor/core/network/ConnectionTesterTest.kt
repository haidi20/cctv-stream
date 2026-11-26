package id.warung.monitor.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTesterTest {

    private val endpoint = RtspEndpoint(
        host = "192.168.1.64",
        port = 554,
        path = "/h264/ch1/sub/av_stream",
        username = "admin",
        secret = "hunter2".toCharArray(),
    )

    private fun tester(
        snapshot: NetworkSnapshot = NetworkSnapshot(NetworkTransport.Wifi, "192.168.1.20"),
        reachable: Boolean = true,
        probe: RtspProbeReport = RtspProbeReport(LayerOutcome.Passed, LayerOutcome.Passed),
        onProbe: (RtspEndpoint) -> Unit = {},
    ) = ConnectionTester(
        network = {
            if (snapshot.transport == NetworkTransport.Wifi && snapshot.ipv4 == "192.168.1.20") snapshot
            else snapshot
        },
        tcp = { _, _, _ -> reachable },
        rtsp = { target, _, _ ->
            onProbe(target)
            probe
        },
    )

    @Test
    fun a_healthy_camera_passes_all_four_layers_in_order() = runTest {
        val report = tester().test(endpoint)

        assertTrue(report.passed)
        assertNull(report.failure)
        assertEquals(ConnectionLayer.entries, report.layers.keys.toList())
        assertTrue(report.layers.values.all { it == LayerOutcome.Passed })
    }

    @Test
    fun a_missing_network_stops_before_any_socket_or_rtsp_attempt() = runTest {
        var probed = false
        val report = ConnectionTester(
            network = { NetworkSnapshot(NetworkTransport.None, ipv4 = null) },
            tcp = { _, _, _ -> probed = true; true },
            rtsp = { _, _, _ -> probed = true; error("must not run") },
        ).test(endpoint)

        assertEquals(ConnectionFailure.NoNetwork, report.failure)
        assertFalse("no probe may run without a link", probed)
        assertEquals(LayerOutcome.Failed(ConnectionFailure.NoNetwork), report.layers[ConnectionLayer.Network])
        assertNotRunAfter(report, ConnectionLayer.Network)
    }

    @Test
    fun cellular_only_is_reported_as_wrong_network_without_probing() = runTest {
        var probed = false
        val report = ConnectionTester(
            network = { NetworkSnapshot(NetworkTransport.Cellular, ipv4 = "10.20.30.40") },
            tcp = { _, _, _ -> probed = true; true },
            rtsp = { _, _, _ -> probed = true; error("must not run") },
        ).test(endpoint)

        assertEquals(ConnectionFailure.WrongNetwork, report.failure)
        assertFalse(probed)
    }

    @Test
    fun wifi_with_a_public_address_is_not_a_private_lan() = runTest {
        val report = ConnectionTester(
            network = { NetworkSnapshot(NetworkTransport.Wifi, "203.0.113.9") },
            tcp = { _, _, _ -> true },
            rtsp = { _, _, _ -> error("must not run") },
        ).test(endpoint)

        assertEquals(ConnectionFailure.WrongNetwork, report.failure)
        assertNotRunAfter(report, ConnectionLayer.Network)
    }

    @Test
    fun an_unreachable_port_stops_before_the_password_is_tried() = runTest {
        var probeCalls = 0
        val report = tester(reachable = false, onProbe = { probeCalls++ }).test(endpoint)

        assertEquals(ConnectionFailure.Unreachable, report.failure)
        assertEquals(0, probeCalls)
        assertEquals(LayerOutcome.Failed(ConnectionFailure.Unreachable), report.layers[ConnectionLayer.Reachability])
        assertNotRunAfter(report, ConnectionLayer.Reachability)
    }

    @Test
    fun a_wrong_password_stops_at_credentials_and_never_reaches_the_stream_layer() = runTest {
        val report = tester(
            probe = RtspProbeReport(
                describe = LayerOutcome.Failed(ConnectionFailure.AuthFailed),
                stream = LayerOutcome.NotRun,
            ),
        ).test(endpoint)

        assertEquals(ConnectionFailure.AuthFailed, report.failure)
        assertEquals(LayerOutcome.NotRun, report.layers[ConnectionLayer.Stream])
    }

    @Test
    fun a_camera_that_answers_but_sends_no_frame_reports_timeout() = runTest {
        val report = tester(
            probe = RtspProbeReport(
                describe = LayerOutcome.Passed,
                stream = LayerOutcome.Failed(ConnectionFailure.Timeout),
            ),
        ).test(endpoint)

        assertEquals(ConnectionFailure.Timeout, report.failure)
        assertFalse(report.passed)
    }

    @Test
    fun a_probe_exception_is_reported_as_protocol_not_as_a_crash() = runTest {
        val report = ConnectionTester(
            network = { NetworkSnapshot(NetworkTransport.Wifi, "192.168.1.20") },
            tcp = { _, _, _ -> true },
            rtsp = { _, _, _ -> error("deserialization exploded at rtsp://admin:hunter2@192.168.1.64") },
        ).test(endpoint)

        assertEquals(ConnectionFailure.Protocol, report.failure)
        assertEquals(LayerOutcome.NotRun, report.layers[ConnectionLayer.Stream])
    }

    @Test
    fun the_test_passes_the_secret_by_reference_and_the_caller_can_wipe_it() = runTest {
        val secret = "hunter2".toCharArray()
        var seen: CharArray? = null
        tester(onProbe = { seen = it.secret }).test(endpoint.copy(secret = secret))

        assertTrue(seen === secret)
        secret.fill('\u0000')
        assertTrue(seen!!.all { it == '\u0000' })
    }

    private fun assertNotRunAfter(report: ConnectionReport, layer: ConnectionLayer) {
        ConnectionLayer.entries.filter { it.ordinal > layer.ordinal }.forEach { later ->
            assertEquals("layer $later must not run", LayerOutcome.NotRun, report.layers[later])
        }
    }
}