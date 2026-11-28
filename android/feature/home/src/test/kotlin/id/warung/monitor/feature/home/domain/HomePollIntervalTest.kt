package id.warung.monitor.feature.home.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The two cadences exist so a walking person's box is refreshed four times
 * faster than the totals (`docs/detection-quality-todo.md` Q-02). These cases
 * pin the arithmetic that keeps a disabled or misconfigured interval from
 * spawning an endless loop on a test's virtual clock.
 */
class HomePollIntervalTest {

    @Test
    fun a_live_stream_asks_for_boxes_more_often_than_for_totals() {
        val interval = HomePollInterval(1_000L, detectionsMillis = 250L)

        assertEquals(250L, interval.detectionMillisOrStatus())
    }

    @Test
    fun a_disabled_poll_disables_the_box_loop_too() {
        assertEquals(0L, HomePollInterval(0L).detectionMillisOrStatus())
        assertEquals(0L, HomePollInterval(0L, detectionsMillis = 250L).detectionMillisOrStatus())
    }

    @Test
    fun a_missing_box_cadence_falls_back_to_the_status_poll() {
        assertEquals(1_000L, HomePollInterval(1_000L, detectionsMillis = 0L).detectionMillisOrStatus())
    }

    @Test
    fun a_box_cadence_slower_than_the_status_poll_is_clamped_to_it() {
        assertEquals(1_000L, HomePollInterval(1_000L, detectionsMillis = 5_000L).detectionMillisOrStatus())
    }

    @Test
    fun an_interval_longer_than_a_minute_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            HomePollInterval(HomePollInterval.MAX_MILLIS + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HomePollInterval(1_000L, detectionsMillis = HomePollInterval.MAX_MILLIS + 1)
        }
    }
}
