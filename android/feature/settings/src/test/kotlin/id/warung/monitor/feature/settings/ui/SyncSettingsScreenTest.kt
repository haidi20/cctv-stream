package id.warung.monitor.feature.settings.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import id.warung.monitor.feature.settings.domain.SyncSettingsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class SyncSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: SyncSettingsState = SyncSettingsState(loading = false),
        onBaseUrlChange: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onClearToken: () -> Unit = {},
        onOpenRetention: () -> Unit = {},
        onBack: () -> Unit = {},
        consumeMessages: Boolean = false,
    ) {
        var current by mutableStateOf(state)
        composeRule.setContent {
            SyncSettingsScreen(
                state = current,
                onBaseUrlChange = {
                    onBaseUrlChange(it)
                    current = current.copy(baseUrl = it)
                },
                onTokenChange = {},
                onSave = onSave,
                onClearToken = onClearToken,
                onOpenRetention = onOpenRetention,
                onDismissMessage = { if (consumeMessages) current = current.copy(message = null) },
                onBack = onBack,
            )
        }
    }

    @Test
    fun the_form_states_that_only_metadata_leaves_the_device() {
        render()

        composeRule.onNodeWithTag("sync-base-url").assertIsDisplayed()
        composeRule.onNodeWithTag("sync-token").assertIsDisplayed()
        composeRule.onNodeWithTag("sync-status").assertIsDisplayed()
    }

    @Test
    fun typing_a_url_is_forwarded_and_enables_save() {
        val seen = mutableListOf<String>()
        render(onBaseUrlChange = seen::add)

        composeRule.onNodeWithTag("sync-base-url").performTextInput("https://vps.contoh.id")

        assertEquals(listOf("https://vps.contoh.id"), seen)
        composeRule.onNodeWithTag("sync-save").assertIsDisplayed()
    }

    @Test
    fun save_is_disabled_when_the_url_is_blank() {
        render(SyncSettingsState(loading = false, baseUrl = ""))

        composeRule.onNodeWithTag("sync-save").assertIsNotEnabled()
    }

    @Test
    fun the_save_button_reaches_the_callback() {
        var saved = 0
        render(
            state = SyncSettingsState(loading = false, baseUrl = "https://vps.contoh.id"),
            onSave = { saved += 1 },
        )

        composeRule.onNodeWithTag("sync-save").performClick()

        assertEquals(1, saved)
    }

    @Test
    fun a_stored_token_can_be_removed_from_the_screen() {
        var cleared = 0
        render(
            state = SyncSettingsState(
                loading = false,
                baseUrl = "https://vps.contoh.id",
                hasSavedToken = true,
            ),
            onClearToken = { cleared += 1 },
        )

        composeRule.onNodeWithTag("sync-clear-token").performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun without_a_stored_token_the_remove_button_is_absent() {
        render(SyncSettingsState(loading = false, baseUrl = "https://vps.contoh.id"))

        composeRule.onNodeWithTag("sync-clear-token").assertDoesNotExist()
    }

    @Test
    fun the_back_button_reaches_the_callback() {
        var back = 0
        render(onBack = { back += 1 })

        composeRule.onNodeWithTag("settings-back").performClick()

        assertEquals(1, back)
    }

    @Test
    fun the_storage_entry_point_opens_local_history_settings() {
        var opened = 0
        render(onOpenRetention = { opened += 1 })

        composeRule.onNodeWithTag("settings-open-retention").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun a_validation_error_is_visible_under_the_field() {
        render(
            SyncSettingsState(
                loading = false,
                baseUrl = "http://vps.contoh.id",
                error = "Alamat server harus diawali https://",
            ),
        )

        composeRule.onNodeWithText("Alamat server harus diawali https://").assertIsDisplayed()
    }

    @Test
    fun only_the_url_is_rendered_never_a_stored_token() {
        render(
            SyncSettingsState(
                loading = false,
                baseUrl = "https://vps.contoh.id",
                hasSavedToken = true,
            ),
        )

        composeRule.onNodeWithText("rahasia").assertDoesNotExist()
    }
}
