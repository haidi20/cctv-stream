package id.warung.monitor.sync

import id.warung.monitor.core.sync.RetentionSettings
import id.warung.monitor.core.sync.RetentionWindow
import id.warung.monitor.data.TableHistoryPruner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The window is read at prune time, not at enqueue time, so a user who shortens
 * retention while the worker is queued sees the shorter window applied.
 */
class HistoryPrunerTest {

    private class FakeSettings(var window: RetentionWindow = RetentionWindow.DEFAULT) :
        RetentionSettings {

        override suspend fun window(): RetentionWindow = window

        override suspend fun save(window: RetentionWindow) {
            this.window = window
        }
    }

    private class RecordingPruner : TableHistoryPruner {
        var seenNow: Long = -1L
        var seenWindow: RetentionWindow? = null
        var removed: Int = 0

        override suspend fun prune(nowMillis: Long, window: RetentionWindow): Int {
            seenNow = nowMillis
            seenWindow = window
            return removed
        }
    }

    @Test
    fun the_saved_window_and_the_current_time_reach_the_prune() = runTest {
        val settings = FakeSettings(RetentionWindow.FOURTEEN_DAYS)
        val pruner = RecordingPruner().apply { removed = 3 }
        val runner = HistoryPruner(settings, pruner, clock = { 5_000L })

        assertEquals(3, runner.run())
        assertEquals(5_000L, pruner.seenNow)
        assertEquals(RetentionWindow.FOURTEEN_DAYS, pruner.seenWindow)
    }

    @Test
    fun a_window_changed_while_the_worker_was_queued_is_honoured() = runTest {
        val settings = FakeSettings(RetentionWindow.THIRTY_DAYS)
        val pruner = RecordingPruner()
        val runner = HistoryPruner(settings, pruner, clock = { 1L })

        settings.window = RetentionWindow.THREE_DAYS
        runner.run()

        assertEquals(RetentionWindow.THREE_DAYS, pruner.seenWindow)
    }

    @Test
    fun zero_removed_is_a_normal_result() = runTest {
        val pruner = RecordingPruner()
        val runner = HistoryPruner(FakeSettings(), pruner, clock = { 1L })

        assertEquals(0, runner.run())
    }
}
