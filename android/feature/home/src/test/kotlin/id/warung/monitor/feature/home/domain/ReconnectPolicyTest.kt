package id.warung.monitor.feature.home.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun transport_failures_are_retried_but_auth_and_protocol_are_not() {
        assertTrue(ReconnectPolicy.isRetryable(StreamErrorKind.Unreachable))
        assertTrue(ReconnectPolicy.isRetryable(StreamErrorKind.Timeout))
        assertTrue(ReconnectPolicy.isRetryable(StreamErrorKind.Closed))
        assertFalse("a 401 must never be retried", ReconnectPolicy.isRetryable(StreamErrorKind.AuthFailed))
        assertFalse("an unsupported codec will not fix itself", ReconnectPolicy.isRetryable(StreamErrorKind.Protocol))
    }

    @Test
    fun waiting_for_the_network_is_not_the_gateway_backoff() {
        assertFalse(ReconnectPolicy.isRetryable(StreamErrorKind.NoNetwork))
        assertFalse(ReconnectPolicy.isRetryable(StreamErrorKind.WrongNetwork))
    }

    @Test
    fun the_delay_grows_and_stops_growing_at_the_cap() {
        val first = ReconnectPolicy.delayMillis(1)
        val second = ReconnectPolicy.delayMillis(2)
        val last = ReconnectPolicy.delayMillis(ReconnectPolicy.MAX_ATTEMPTS)
        assertTrue("backoff must grow", second > first)
        assertEquals("attempts beyond the list keep the cap", last, ReconnectPolicy.delayMillis(20))
    }

    @Test
    fun a_zero_or_negative_attempt_is_rejected() {
        var rejected = false
        try {
            ReconnectPolicy.delayMillis(0)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}