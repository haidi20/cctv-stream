package id.warung.monitor.feature.auth.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class PinKeypadHapticTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class RecordingHaptics : HapticFeedback {
        val requests = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            requests += hapticFeedbackType
        }
    }

    private fun render(enabled: Boolean, haptics: RecordingHaptics) {
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                PinKeypad(onDigit = {}, onDelete = {}, enabled = enabled)
            }
        }
    }

    @Test
    fun a_digit_press_requests_one_text_handle_move_tick() {
        val haptics = RecordingHaptics()
        render(enabled = true, haptics = haptics)

        composeRule.onNodeWithContentDescription("Angka 7").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.TextHandleMove), haptics.requests)
        }
    }

    @Test
    fun the_delete_key_also_ticks() {
        val haptics = RecordingHaptics()
        render(enabled = true, haptics = haptics)

        composeRule.onNodeWithContentDescription("Hapus satu digit").performClick()

        composeRule.runOnIdle { assertEquals(1, haptics.requests.size) }
    }

    @Test
    fun a_disabled_keypad_never_ticks() {
        val haptics = RecordingHaptics()
        render(enabled = false, haptics = haptics)

        composeRule.onNodeWithContentDescription("Angka 7").performClick()

        composeRule.runOnIdle { assertEquals(0, haptics.requests.size) }
    }
}