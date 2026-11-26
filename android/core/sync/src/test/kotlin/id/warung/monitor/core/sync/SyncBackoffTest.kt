package id.warung.monitor.core.sync

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `docs/monitoring.md` section 4.3 fixes the ladder at 30 s, 1 min, 5 min, and
 * a 15 min ceiling. A drift here would hammer the VPS or delay reconciliation.
 */
class SyncBackoffTest {

    @Test
    fun the_first_failure_waits_thirty_seconds() {
        assertEquals(30.seconds, SyncBackoff.delayFor(0))
    }

    @Test
    fun the_ladder_follows_the_documented_order() {
        assertEquals(listOf(30.seconds, 1.minutes, 5.minutes), SyncBackoff.steps)
        assertEquals(1.minutes, SyncBackoff.delayFor(1))
        assertEquals(5.minutes, SyncBackoff.delayFor(2))
    }

    @Test
    fun the_delay_never_exceeds_fifteen_minutes() {
        assertEquals(15.minutes, SyncBackoff.delayFor(3))
        assertEquals(15.minutes, SyncBackoff.delayFor(99))
    }

    @Test
    fun a_negative_count_still_waits_instead_of_retrying_immediately() {
        assertEquals(30.seconds, SyncBackoff.delayFor(-1))
    }
}

