package id.warung.monitor.sync

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `docs/android-sync.md` section 8: a burst of transitions must collapse into
 * one debounced request, and the documented ladder must survive the fact that
 * WorkManager's unique-work policy can silently drop follow-up requests.
 *
 * Only the pure queue decision is asserted; the delay is not observable from
 * `WorkInfo` in Robolectric, so a fake WorkManager would prove nothing.
 */
class SyncSchedulerTest {

    @Test
    fun a_transition_burst_is_debounced_and_kept() {
        val plan = SyncScheduler.planDebounced()

        assertEquals(ExistingWorkPolicy.KEEP, plan.policy)
        assertEquals(30_000L, plan.delayMillis)
    }

    @Test
    fun a_follow_up_run_is_appended_so_the_running_worker_is_not_replaced() {
        val plan = SyncScheduler.planImmediate()

        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, plan.policy)
        assertEquals(0L, plan.delayMillis)
    }

    @Test
    fun each_ladder_rung_is_kept_verbatim_after_a_failure() {
        val ladder = listOf(30_000L, 60_000L, 300_000L, 900_000L)

        ladder.forEach { delay ->
            val plan = SyncScheduler.planRetry(delay)
            assertEquals(delay, plan.delayMillis)
            assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, plan.policy)
        }
    }

    @Test
    fun a_tiny_or_negative_delay_is_clamped_so_the_worker_cannot_busy_loop() {
        assertEquals(10_000L, SyncScheduler.planRetry(0L).delayMillis)
        assertEquals(10_000L, SyncScheduler.planRetry(-1L).delayMillis)
        assertEquals(10_000L, SyncScheduler.planRetry(1L).delayMillis)
        assertEquals(10_000L, SyncScheduler.planRetry(SyncScheduler.MIN_DELAY_MILLIS).delayMillis)
    }

    @Test
    fun a_user_initiated_sync_replaces_a_pending_long_retry() {
        val plan = SyncScheduler.planRestart()

        assertEquals(ExistingWorkPolicy.REPLACE, plan.policy)
        assertEquals(0L, plan.delayMillis)
    }
}
