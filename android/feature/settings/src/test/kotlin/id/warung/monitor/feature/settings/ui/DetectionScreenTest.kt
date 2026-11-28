package id.warung.monitor.feature.settings.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import id.warung.monitor.core.model.DetectionPreset
import id.warung.monitor.feature.settings.domain.DetectionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class DetectionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: DetectionState = DetectionState(loading = false),
        onSelect: (DetectionPreset) -> Unit = {},
        onSave: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        var current by mutableStateOf(state)
        composeRule.setContent {
            DetectionScreen(
                state = current,
                onSelect = {
                    onSelect(it)
                    current = current.copy(preset = it)
                },
                onSave = onSave,
                onDismissMessage = {},
                onBack = onBack,
            )
        }
    }

    @Test
    fun both_presets_are_listed_with_an_explanation() {
        render()

        composeRule.onNodeWithTag("detection-option-seimbang").assertIsDisplayed()
        composeRule.onNodeWithTag("detection-option-presisi").assertIsDisplayed()
    }

    @Test
    fun selecting_the_other_preset_reaches_the_callback() {
        val seen = mutableListOf<DetectionPreset>()
        render(onSelect = seen::add)

        composeRule.onNodeWithTag("detection-option-presisi").performClick()

        assertEquals(listOf(DetectionPreset.Precise), seen)
    }

    @Test
    fun save_is_disabled_until_the_choice_changes() {
        render(DetectionState(loading = false))

        composeRule.onNodeWithTag("detection-save").assertIsNotEnabled()
    }

    @Test
    fun save_is_enabled_after_a_change_and_reaches_the_callback() {
        var saved = 0
        render(
            state = DetectionState(loading = false, preset = DetectionPreset.Precise),
            onSave = { saved += 1 },
        )

        composeRule.onNodeWithTag("detection-save").performClick()

        assertEquals(1, saved)
    }

    @Test
    fun an_unknown_stored_value_shows_a_note() {
        render(
            DetectionState(loading = false, unknownStoredValue = true),
        )

        composeRule.onNodeWithTag("detection-unknown-note").assertIsDisplayed()
    }

    @Test
    fun the_note_disappears_once_the_user_picks_a_value() {
        render(
            DetectionState(
                loading = false,
                preset = DetectionPreset.Precise,
                unknownStoredValue = true,
            ),
        )

        composeRule.onNodeWithTag("detection-unknown-note").assertDoesNotExist()
    }

    @Test
    fun a_write_error_is_visible() {
        render(
            DetectionState(
                loading = false,
                preset = DetectionPreset.Precise,
                error = "Gagal menyimpan pilihan deteksi",
            ),
        )

        composeRule.onNodeWithTag("detection-error").assertIsDisplayed()
    }

    @Test
    fun the_back_button_reaches_the_callback() {
        var back = 0
        render(onBack = { back += 1 })

        composeRule.onNodeWithTag("detection-back").performClick()

        assertEquals(1, back)
    }
}
