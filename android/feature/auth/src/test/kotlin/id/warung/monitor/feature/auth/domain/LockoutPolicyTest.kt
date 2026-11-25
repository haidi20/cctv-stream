package id.warung.monitor.feature.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockoutPolicyTest {

    private val policy = LockoutPolicy()

    @Test
    fun no_lockout_below_five_failures() {
        (0..4).forEach { assertNull("failures=$it", policy.lockMillisFor(it)) }
    }

    @Test
    fun thresholds_are_30s_5m_and_30m() {
        assertEquals(30_000L, policy.lockMillisFor(5))
        assertEquals(30_000L, policy.lockMillisFor(9))
        assertEquals(5 * 60_000L, policy.lockMillisFor(10))
        assertEquals(5 * 60_000L, policy.lockMillisFor(14))
        assertEquals(30 * 60_000L, policy.lockMillisFor(15))
        assertEquals(30 * 60_000L, policy.lockMillisFor(100))
    }

    @Test
    fun attempts_left_points_at_the_next_threshold() {
        assertEquals(5, policy.attemptsLeft(0))
        assertEquals(1, policy.attemptsLeft(4))
        assertEquals(5, policy.attemptsLeft(5))
        assertEquals(1, policy.attemptsLeft(9))
        assertEquals(1, policy.attemptsLeft(14))
    }
}