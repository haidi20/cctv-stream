package id.warung.monitor.feature.auth.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import id.warung.monitor.feature.auth.domain.LoginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class LoginScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: LoginState,
        onDigit: (Char) -> Unit = {},
        onDelete: () -> Unit = {},
        onBiometric: (() -> Unit)? = null,
        onReset: (String) -> Unit = {},
        onStartChangePin: () -> Unit = {},
        onCancelChangePin: () -> Unit = {},
        onStartResetPin: () -> Unit = {},
        onCancelResetPin: () -> Unit = {},
    ) {
        composeRule.setContent {
            LoginScreen(
                state = state,
                onDigit = onDigit,
                onDelete = onDelete,
                onBiometric = onBiometric,
                onReset = onReset,
                onStartChangePin = onStartChangePin,
                onCancelChangePin = onCancelChangePin,
                onStartResetPin = onStartResetPin,
                onCancelResetPin = onCancelResetPin,
            )
        }
    }

    @Test
    fun every_keypad_digit_reaches_the_callback() {
        val typed = mutableListOf<Char>()
        render(LoginState.EnterPin(), onDigit = { typed += it })

        (0..9).forEach { digit ->
            composeRule.onNodeWithContentDescription("Angka $digit").performClick()
        }

        composeRule.runOnIdle {
            assertEquals("0123456789", typed.joinToString(""))
        }
    }

    @Test
    fun delete_key_calls_the_delete_callback() {
        var deletes = 0
        render(LoginState.EnterPin(filled = 3), onDelete = { deletes++ })

        composeRule.onNodeWithContentDescription("Hapus satu digit").performClick()

        composeRule.runOnIdle { assertEquals(1, deletes) }
    }

    @Test
    fun wrong_pin_message_is_visible_after_a_failure() {
        render(LoginState.EnterPin(filled = 0, attemptsLeft = 3, message = "PIN salah. Sisa 3 percobaan."))

        composeRule.onNodeWithText("PIN salah. Sisa 3 percobaan.").assertExists()
    }

    @Test
    fun lockout_disables_the_keypad_and_shows_a_countdown() {
        render(LoginState.LockedOut(remainingMs = 125_000))

        composeRule.onNodeWithTag("lockout-countdown").assertExists()
        composeRule.onNodeWithContentDescription("Angka 1").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Hapus satu digit").assertIsNotEnabled()
    }

    @Test
    fun verifying_disables_the_keypad() {
        render(LoginState.Verifying())

        composeRule.onNodeWithContentDescription("Angka 5").assertIsNotEnabled()
    }

    @Test
    fun biometric_key_is_absent_when_no_callback_is_supplied() {
        render(LoginState.EnterPin())

        composeRule.onNodeWithContentDescription("Masuk dengan biometrik").assertDoesNotExist()
    }

    @Test
    fun biometric_key_runs_the_callback_when_supplied() {
        var prompts = 0
        render(LoginState.EnterPin(), onBiometric = { prompts++ })

        composeRule.onNodeWithContentDescription("Masuk dengan biometrik").performClick()

        composeRule.runOnIdle { assertEquals(1, prompts) }
    }

    @Test
    fun create_pin_screen_shows_the_first_step_prompt() {
        render(LoginState.CreatePin(LoginState.CreatePinStep.First))

        composeRule.onNodeWithText("Ketik PIN baru").assertExists()
        composeRule.onNodeWithText("Buat PIN 6 digit untuk mengunci aplikasi").assertExists()
    }

    @Test
    fun ganti_pin_button_opens_the_change_flow() {
        var started = 0
        render(LoginState.EnterPin(), onStartChangePin = { started++ })

        composeRule.onNodeWithText("Ganti PIN").performClick()

        composeRule.runOnIdle { assertEquals(1, started) }
    }

    @Test
    fun change_pin_step_prompts_and_cancel_returns_to_the_keypad() {
        var cancels = 0
        render(
            LoginState.ChangePin(LoginState.ChangePinStep.Current),
            onCancelChangePin = { cancels++ },
        )

        composeRule.onNodeWithText("Ketik PIN lama").assertExists()
        composeRule.onNodeWithText("Ganti PIN").assertExists()

        composeRule.onNodeWithTag("change-pin-cancel").performClick()

        composeRule.runOnIdle { assertEquals(1, cancels) }
    }

    @Test
    fun new_pin_step_asks_for_the_new_pin() {
        render(LoginState.ChangePin(LoginState.ChangePinStep.New))

        composeRule.onNodeWithText("Ketik PIN baru").assertExists()
    }

    @Test
    fun confirm_step_asks_to_repeat_the_new_pin() {
        render(LoginState.ChangePin(LoginState.ChangePinStep.Confirm))

        composeRule.onNodeWithText("Ketik ulang PIN baru").assertExists()
    }

    @Test
    fun change_pin_error_is_visible() {
        render(
            LoginState.ChangePin(
                LoginState.ChangePinStep.Current,
                error = "PIN lama salah. Sisa 4 percobaan.",
            ),
        )

        composeRule.onNodeWithText("PIN lama salah. Sisa 4 percobaan.").assertExists()
    }

    @Test
    fun reset_stays_disabled_until_HAPUS_is_typed() {
        var resetWith: String? = null
        render(LoginState.EnterPin(), onReset = { resetWith = it })

        composeRule.onNodeWithText("Lupa PIN?").performClick()
        composeRule.onNodeWithTag("reset-confirmation").performTextReplacement("hapus")
        composeRule.onNodeWithText("Hapus semua data aplikasi").assertIsNotEnabled()

        composeRule.onNodeWithTag("reset-confirmation").performTextReplacement("HAPUS")
        composeRule.onNodeWithText("Hapus semua data aplikasi").assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals("HAPUS", resetWith) }
    }

    @Test
    fun a_wrong_reset_word_never_reaches_the_eraser() {
        var resetWith: String? = null
        render(LoginState.EnterPin(), onReset = { resetWith = it })

        composeRule.onNodeWithText("Lupa PIN?").performClick()
        composeRule.onNodeWithTag("reset-confirmation").performTextReplacement("HAPUS SEMUA")

        composeRule.onNodeWithText("Hapus semua data aplikasi").assertIsNotEnabled()
        composeRule.runOnIdle { assertNull(resetWith) }
    }

    @Test
    fun typing_reset_on_the_keypad_screen_opens_the_pin_reset() {
        var opened = 0
        render(LoginState.EnterPin(), onStartResetPin = { opened++ })

        composeRule.onNodeWithTag("reset-word").performTextReplacement("reset")

        composeRule.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun reset_pin_shows_the_two_form_steps_and_cancel() {
        var cancels = 0
        render(
            LoginState.ResetPin(LoginState.ResetPinStep.New),
            onCancelResetPin = { cancels++ },
        )

        composeRule.onNodeWithText("Atur ulang PIN").assertExists()
        composeRule.onNodeWithText("Ketik PIN baru").assertExists()

        composeRule.onNodeWithTag("reset-pin-cancel").performClick()
        composeRule.runOnIdle { assertEquals(1, cancels) }
    }

    @Test
    fun reset_pin_confirm_step_asks_to_repeat_the_new_pin() {
        render(LoginState.ResetPin(LoginState.ResetPinStep.Confirm))

        composeRule.onNodeWithText("Ketik ulang PIN baru").assertExists()
    }
}
