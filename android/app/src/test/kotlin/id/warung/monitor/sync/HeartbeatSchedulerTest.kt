package id.warung.monitor.sync

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `docs/android-sync.md` section 8 wants a 30-60 s heartbeat, which a
 * `PeriodicWorkRequest` cannot express (Android floors it at 15 min). Only the
 * pure chain decision is asserted here; the actual delay is not visible in
 * `WorkInfo`, so a Robolectric check would prove nothing.
 */
class HeartbeatSchedulerTest {

    @Test
    fun the_first_heartbeat_replaces_any_stale_chain_and_waits_one_live_interval() {
        val plan = HeartbeatScheduler.planFirst()

        assertEquals(ExistingWorkPolicy.REPLACE, plan.policy)
        assertEquals(30_000L, plan.delayMillis)
    }

    @Test
    fun the_live_and_idle_cadences_match_the_contract() {
        assertEquals(30_000L, HeartbeatScheduler.LIVE_INTERVAL_MILLIS)
        assertEquals(60_000L, HeartbeatScheduler.IDLE_INTERVAL_MILLIS)
    }

    @Test
    fun the_next_heartbeat_is_appended_because_the_worker_is_still_running() {
        val plan = HeartbeatScheduler.planNext(HeartbeatScheduler.LIVE_INTERVAL_MILLIS)

        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, plan.policy)
        assertEquals(30_000L, plan.delayMillis)
    }

    @Test
    fun a_tiny_or_negative_delay_is_clamped_so_the_chain_cannot_busy_loop() {
        assertEquals(10_000L, HeartbeatScheduler.planNext(0L).delayMillis)
        assertEquals(10_000L, HeartbeatScheduler.planNext(-1L).delayMillis)
        assertEquals(10_000L, HeartbeatScheduler.planNext(1L).delayMillis)
    }
}
