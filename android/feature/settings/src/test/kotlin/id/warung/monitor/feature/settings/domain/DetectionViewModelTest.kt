package id.warung.monitor.feature.settings.domain

import id.warung.monitor.core.model.DetectionPreset
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
 * The screen is the only way to change the detection preset, so a bug here
 * leaves the phone stuck on a threshold nobody can fix
 * (`docs/detection-quality-todo.md` Q-05).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetectionViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun a_fresh_install_shows_the_balanced_default() = runTest {
        val viewModel = DetectionViewModel(FakeDetectionSettings())
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertEquals(DetectionPreset.Balanced, state.preset)
        assertEquals(DetectionPreset.Balanced, state.savedPreset)
        assertFalse(state.canSave)
        assertFalse(state.showsUnknownValueNote)
    }

    @Test
    fun a_stored_preset_is_loaded_as_the_saved_value() = runTest {
        val viewModel = DetectionViewModel(FakeDetectionSettings(DetectionPreset.Precise))
        advanceUntilIdle()

        assertEquals(DetectionPreset.Precise, viewModel.state.value.savedPreset)
        assertFalse(viewModel.state.value.canSave)
    }

    @Test
    fun picking_a_different_preset_enables_save() = runTest {
        val viewModel = DetectionViewModel(FakeDetectionSettings())
        advanceUntilIdle()

        viewModel.select(DetectionPreset.Precise)

        assertTrue(viewModel.state.value.canSave)
        assertEquals(DetectionPreset.Precise, viewModel.state.value.preset)
    }

    @Test
    fun saving_stores_the_choice_and_reports_success() = runTest {
        val settings = FakeDetectionSettings()
        val viewModel = DetectionViewModel(settings)
        advanceUntilIdle()

        var saved = 0
        viewModel.select(DetectionPreset.Precise)
        viewModel.save { saved += 1 }
        advanceUntilIdle()

        assertEquals(listOf(DetectionPreset.Precise), settings.saved)
        assertEquals(1, saved)
        assertEquals(DetectionPreset.Precise, viewModel.state.value.savedPreset)
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.canSave)
    }

    @Test
    fun saving_without_a_change_does_not_write() = runTest {
        val settings = FakeDetectionSettings()
        val viewModel = DetectionViewModel(settings)
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertTrue(settings.saved.isEmpty())
    }

    @Test
    fun an_unreadable_stored_value_is_flagged_until_the_user_saves() = runTest {
        val viewModel = DetectionViewModel(
            FakeDetectionSettings(unknownStoredValue = true),
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.showsUnknownValueNote)

        // Balanced is also the fallback, so picking it changes nothing; only a
        // genuinely different choice replaces the note.
        viewModel.select(DetectionPreset.Balanced)
        assertTrue(viewModel.state.value.showsUnknownValueNote)

        viewModel.select(DetectionPreset.Precise)
        assertFalse("a pending change replaces the note", viewModel.state.value.showsUnknownValueNote)
    }

    @Test
    fun a_write_failure_is_reported_and_save_stays_available() = runTest {
        val viewModel = DetectionViewModel(FakeDetectionSettings(failWrites = true))
        advanceUntilIdle()

        viewModel.select(DetectionPreset.Precise)
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("Gagal menyimpan pilihan deteksi", state.error)
        assertFalse(state.saving)
        assertTrue(state.canSave)
    }

    @Test
    fun a_read_failure_still_leaves_a_usable_form() = runTest {
        val viewModel = DetectionViewModel(FakeDetectionSettings(failReads = true))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(DetectionPreset.Balanced, state.preset)
    }
}
