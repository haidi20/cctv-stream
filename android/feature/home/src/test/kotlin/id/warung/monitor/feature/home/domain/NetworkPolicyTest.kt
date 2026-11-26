package id.warung.monitor.feature.home.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    @Test
    fun wifi_with_a_private_address_allows_an_rtsp_camera() {
        assertNull(NetworkPolicy.blockReason(NetworkReach.WifiPrivate, sourceIsRtsp = true))
    }

    @Test
    fun cellular_is_wrong_network_and_not_a_camera_failure() {
        assertEquals(
            StreamErrorKind.WrongNetwork,
            NetworkPolicy.blockReason(NetworkReach.CellularOnly, sourceIsRtsp = true),
        )
    }

    @Test
    fun no_link_is_no_network() {
        assertEquals(
            StreamErrorKind.NoNetwork,
            NetworkPolicy.blockReason(NetworkReach.Offline, sourceIsRtsp = true),
        )
    }

    @Test
    fun the_demo_source_is_never_blocked_by_the_network() {
        assertNull(NetworkPolicy.blockReason(NetworkReach.Offline, sourceIsRtsp = false))
        assertNull(NetworkPolicy.blockReason(NetworkReach.CellularOnly, sourceIsRtsp = false))
    }

    @Test
    fun only_network_failures_are_retried_when_the_link_returns() {
        assertTrue(NetworkPolicy.shouldRetryAfterNetworkReturns(StreamErrorKind.NoNetwork))
        assertTrue(NetworkPolicy.shouldRetryAfterNetworkReturns(StreamErrorKind.WrongNetwork))
        assertFalse("a 401 must never retry", NetworkPolicy.shouldRetryAfterNetworkReturns(StreamErrorKind.AuthFailed))
        assertFalse(NetworkPolicy.shouldRetryAfterNetworkReturns(StreamErrorKind.Unreachable))
        assertFalse(NetworkPolicy.shouldRetryAfterNetworkReturns(null))
    }

    @Test
    fun the_messages_name_the_network_not_the_camera() {
        assertTrue(StreamErrorKind.NoNetwork.message.contains("Wi-Fi"))
        assertTrue(StreamErrorKind.WrongNetwork.message.contains("Wi-Fi"))
        assertFalse(StreamErrorKind.NoNetwork.message.contains("kamera"))
    }
    @Test
    fun only_an_auth_failure_stays_on_screen_when_the_transport_drops() {
        assertFalse(NetworkPolicy.shouldShowTransportProblem(StreamErrorKind.AuthFailed))
        assertTrue(NetworkPolicy.shouldShowTransportProblem(StreamErrorKind.Timeout))
        assertTrue(NetworkPolicy.shouldShowTransportProblem(StreamErrorKind.Unreachable))
        assertTrue(NetworkPolicy.shouldShowTransportProblem(null))
    }
}