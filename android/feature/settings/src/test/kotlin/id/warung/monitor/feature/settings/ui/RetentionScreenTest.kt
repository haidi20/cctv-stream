package id.warung.monitor.feature.settings.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import id.warung.monitor.core.sync.RetentionWindow
import id.warung.monitor.feature.settings.domain.RetentionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class RetentionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: RetentionState = RetentionState(
            loading = false,
            rows = 12,
            databaseBytes = 4_096L,
        ),
        onSelect: (RetentionWindow) -> Unit = {},
        onSave: () -> Unit = {},
    ) {
        var current by mutableStateOf(state)
        composeRule.setContent {
            RetentionScreen(
                state = current,
                onSelect = {
                    onSelect(it)
                    current = current.copy(window = it)
                },
                onSave = onSave,
                onDismissMessage = { current = current.copy(message = null) },
                onBack = {},
            )
        }
    }

    @Test
    fun the_storage_counters_and_every_documented_window_are_shown() {
        render()

        composeRule.onNodeWithText("Riwayat lokal").assertIsDisplayed()
        composeRule.onNodeWithText("12 event tersimpan").assertIsDisplayed()
        composeRule.onNodeWithText("Ukuran basis data 4.0 KB").assertIsDisplayed()
        listOf(3, 7, 14, 30).forEach { days ->
            composeRule.onNodeWithTag("retention-option-$days").assertIsDisplayed()
        }
    }

    @Test
    fun choosing_a_window_reaches_the_callback() {
        val selected = mutableListOf<RetentionWindow>()
        render(onSelect = selected::add)

        composeRule.onNodeWithTag("retention-option-30").performClick()

        assertEquals(listOf(RetentionWindow.THIRTY_DAYS), selected)
    }

    @Test
    fun the_three_day_window_warns_that_local_history_disappears_sooner() {
        render(
            RetentionState(
                loading = false,
                window = RetentionWindow.THREE_DAYS,
                savedWindow = RetentionWindow.SEVEN_DAYS,
            ),
        )

        composeRule.onNodeWithTag("retention-warning").assertIsDisplayed()
    }

    @Test
    fun save_stays_disabled_while_the_window_is_unchanged() {
        render()

        composeRule.onNodeWithTag("retention-save").assertIsNotEnabled()
    }

    @Test
    fun save_is_enabled_after_a_change_and_reaches_the_callback() {
        var saved = 0
        render(onSave = { saved += 1 })

        composeRule.onNodeWithTag("retention-option-14").performClick()
        composeRule.onNodeWithTag("retention-save").assertIsEnabled()
        composeRule.onNodeWithTag("retention-save").performClick()

        assertEquals(1, saved)
    }

    @Test
    fun reaching_the_row_limit_is_reported_instead_of_hidden() {
        render(
            RetentionState(
                loading = false,
                rows = 10_000,
                rowLimit = 10_000,
                databaseBytes = 2_048L,
            ),
        )

        composeRule.onNodeWithText(
            "Batas 10000 baris tersimpan tercapai; event terlama dihapus lebih dulu.",
        ).assertIsDisplayed()
    }
}
