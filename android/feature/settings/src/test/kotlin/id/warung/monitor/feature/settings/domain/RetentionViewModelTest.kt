package id.warung.monitor.feature.settings.domain

import id.warung.monitor.core.sync.LocalHistoryStats
import id.warung.monitor.core.sync.LocalHistoryStore
import id.warung.monitor.core.sync.RetentionSettings
import id.warung.monitor.core.sync.RetentionWindow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * `docs/android-sync.md` section 7: the choice is stored, never deleted while
 * the screen is open, and a failed read must still leave a usable form.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetentionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeRetentionSettings(
        var window: RetentionWindow = RetentionWindow.DEFAULT,
        var failWrites: Boolean = false,
        var failReads: Boolean = false,
    ) : RetentionSettings {

        val saved = mutableListOf<RetentionWindow>()

        override suspend fun window(): RetentionWindow {
            if (failReads) throw IllegalStateException("read failed")
            return window
        }

        override suspend fun save(window: RetentionWindow) {
            if (failWrites) throw IllegalStateException("write failed")
            saved += window
            this.window = window
        }
    }

    private class FakeHistoryStore(
        var stats: LocalHistoryStats = LocalHistoryStats(rows = 12, databaseBytes = 4_096L),
        var failReads: Boolean = false,
    ) : LocalHistoryStore {

        override suspend fun stats(): LocalHistoryStats {
            if (failReads) throw IllegalStateException("stats failed")
            return stats
        }
    }

    @Test
    fun loading_reports_the_saved_window_and_the_storage_counters() = runTest {
        val viewModel = RetentionViewModel(
            FakeRetentionSettings(window = RetentionWindow.FOURTEEN_DAYS),
            FakeHistoryStore(),
        )

        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertEquals(RetentionWindow.FOURTEEN_DAYS, state.window)
        assertEquals(RetentionWindow.FOURTEEN_DAYS, state.savedWindow)
        assertEquals(12, state.rows)
        assertEquals(4_096L, state.databaseBytes)
        assertFalse("nothing changed yet", state.canSave)
    }

    @Test
    fun choosing_a_different_window_enables_save() = runTest {
        val viewModel = RetentionViewModel(FakeRetentionSettings(), FakeHistoryStore())
        advanceUntilIdle()

        viewModel.select(RetentionWindow.THIRTY_DAYS)

        assertTrue(viewModel.state.value.canSave)
        assertTrue(viewModel.state.value.hasChange)
    }

    @Test
    fun saving_persists_the_window_and_reports_success() = runTest {
        val settings = FakeRetentionSettings()
        val viewModel = RetentionViewModel(settings, FakeHistoryStore())
        advanceUntilIdle()
        var savedCallbacks = 0

        viewModel.select(RetentionWindow.THIRTY_DAYS)
        viewModel.save { savedCallbacks += 1 }
        advanceUntilIdle()

        assertEquals(listOf(RetentionWindow.THIRTY_DAYS), settings.saved)
        assertEquals(1, savedCallbacks)
        val state = viewModel.state.value
        assertEquals(RetentionWindow.THIRTY_DAYS, state.savedWindow)
        assertFalse(state.hasChange)
        assertFalse(state.saving)
        assertNull(state.error)
        assertTrue(state.message.orEmpty().contains("30 hari"))
    }

    @Test
    fun saving_without_a_change_never_touches_storage() = runTest {
        val settings = FakeRetentionSettings()
        val viewModel = RetentionViewModel(settings, FakeHistoryStore())
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertTrue(settings.saved.isEmpty())
    }

    @Test
    fun a_failed_save_keeps_the_previous_window_and_shows_an_error() = runTest {
        val settings = FakeRetentionSettings(failWrites = true)
        val viewModel = RetentionViewModel(settings, FakeHistoryStore())
        advanceUntilIdle()
        var savedCallbacks = 0

        viewModel.select(RetentionWindow.THREE_DAYS)
        viewModel.save { savedCallbacks += 1 }
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(0, savedCallbacks)
        assertEquals(RetentionWindow.DEFAULT, state.savedWindow)
        assertEquals(RetentionWindow.THREE_DAYS, state.window)
        assertEquals("Gagal menyimpan masa simpan", state.error)
        assertFalse(state.saving)
    }

    @Test
    fun unreadable_counters_still_let_the_form_load_and_save() = runTest {
        val settings = FakeRetentionSettings()
        val viewModel = RetentionViewModel(settings, FakeHistoryStore(failReads = true))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading)
        assertEquals(RetentionWindow.DEFAULT, viewModel.state.value.window)

        viewModel.select(RetentionWindow.FOURTEEN_DAYS)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(listOf(RetentionWindow.FOURTEEN_DAYS), settings.saved)
    }

    @Test
    fun an_unreadable_window_still_opens_the_screen() = runTest {
        val viewModel = RetentionViewModel(
            FakeRetentionSettings(failReads = true),
            FakeHistoryStore(),
        )

        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertEquals(RetentionWindow.DEFAULT, state.window)
        assertFalse(state.canSave)
    }
}
