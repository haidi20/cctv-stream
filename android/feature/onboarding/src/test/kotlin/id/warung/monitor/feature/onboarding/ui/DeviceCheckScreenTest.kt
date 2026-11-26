package id.warung.monitor.feature.onboarding.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import id.warung.monitor.feature.onboarding.domain.CheckStatus
import id.warung.monitor.feature.onboarding.domain.DeviceCheckId
import id.warung.monitor.feature.onboarding.domain.DeviceCheckRow
import id.warung.monitor.feature.onboarding.domain.DeviceErrorCode
import id.warung.monitor.feature.onboarding.domain.DeviceProfile
import id.warung.monitor.feature.onboarding.domain.DeviceReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class DeviceCheckScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun report(
        profile: DeviceProfile = DeviceProfile.Full,
        engineStatus: CheckStatus = CheckStatus.Pass,
        storageStatus: CheckStatus = CheckStatus.Pass,
        blockedCode: String? = null,
    ) = DeviceReport(
        profile = profile,
        rows = listOf(
            DeviceCheckRow(DeviceCheckId.Platform, CheckStatus.Pass, "Android 13 dengan prosesor ARM 64-bit"),
            DeviceCheckRow(DeviceCheckId.Memory, CheckStatus.Pass, "RAM 4096 MB"),
            DeviceCheckRow(
                DeviceCheckId.Engine,
                engineStatus,
                "Mesin AI siap (warm-up 120 ms)",
                technical = "Pustaka native: dimuat",
                errorCode = if (engineStatus == CheckStatus.Unsupported) blockedCode else null,
            ),
            DeviceCheckRow(DeviceCheckId.Storage, storageStatus, "Penyimpanan 4096 MB bebas"),
        ),
        engineReady = engineStatus == CheckStatus.Pass,
        blockedCode = blockedCode,
        appVersionCode = 1,
        appVersionName = "0.1.0",
    )

    private fun render(
        state: OnboardingUiState,
        checking: Boolean = false,
        onRunCheck: () -> Unit = {},
        onContinue: () -> Unit = {},
        onRetry: () -> Unit = {},
        onCopyReport: () -> Unit = {},
        onCloseApp: () -> Unit = {},
    ) {
        composeRule.setContent {
            DeviceCheckScreen(
                state = state,
                checking = checking,
                onRunCheck = onRunCheck,
                onContinue = onContinue,
                onRetry = onRetry,
                onCopyReport = onCopyReport,
                onCloseApp = onCloseApp,
            )
        }
    }

    @Test
    fun intro_screen_shows_the_run_button() {
        render(OnboardingUiState.Check)

        composeRule.onNodeWithText("Pemeriksaan perangkat").assertIsDisplayed()
        composeRule.onNodeWithTag("device-check-run").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun checking_disables_the_run_button_and_shows_progress() {
        render(OnboardingUiState.Check, checking = true)

        composeRule.onNodeWithTag("device-check-run").assertIsNotEnabled()
        composeRule.onNodeWithTag("device-check-progress").assertIsDisplayed()
        composeRule.onNodeWithText("Memeriksa…").assertIsDisplayed()
    }

    @Test
    fun warning_report_shows_amber_note_and_continue_button() {
        var continued = false
        render(
            state = OnboardingUiState.ReportBody(
                report(
                    profile = DeviceProfile.Balanced,
                    storageStatus = CheckStatus.Warning,
                ),
            ),
            onContinue = { continued = true },
        )

        composeRule.onNodeWithTag("device-check-warning").assertIsDisplayed()
        composeRule.onNodeWithTag("device-check-continue").assertIsEnabled().performClick()
        assertTrue(continued)
    }

    @Test
    fun blocked_report_hides_continue_and_shows_copy_and_close() {
        var copied = false
        var closed = false
        render(
            state = OnboardingUiState.ReportBody(
                report(
                    profile = DeviceProfile.Blocked,
                    engineStatus = CheckStatus.Unsupported,
                    blockedCode = DeviceErrorCode.LIBRARY_LOAD,
                ),
            ),
            onCopyReport = { copied = true },
            onCloseApp = { closed = true },
        )

        composeRule.onNodeWithTag("device-check-continue").assertDoesNotExist()
        composeRule.onNodeWithTag("device-check-blocked").assertIsDisplayed()
        composeRule.onNodeWithTag("device-check-copy").performClick()
        composeRule.onNodeWithTag("device-check-close").performClick()
        assertTrue(copied)
        assertTrue(closed)
    }

    @Test
    fun technical_detail_is_collapsed_until_requested() {
        render(OnboardingUiState.ReportBody(report()))

        composeRule.onNodeWithText("Pustaka native: dimuat").assertDoesNotExist()
        composeRule.onNodeWithTag("device-check-detail-Engine").performClick()
        composeRule.onNodeWithText("Pustaka native: dimuat").assertIsDisplayed()
    }

    @Test
    fun passing_report_offers_a_recheck_without_warning_copy() {
        var ran = false
        render(
            state = OnboardingUiState.ReportBody(report()),
            onRunCheck = { ran = true },
        )

        composeRule.onNodeWithTag("device-check-warning").assertDoesNotExist()
        composeRule.onNodeWithTag("device-check-continue").assertDoesNotExist()
        composeRule.onNodeWithTag("device-check-run").performClick()
        assertTrue(ran)
    }

    @Test
    fun every_row_title_is_rendered_once() {
        render(OnboardingUiState.ReportBody(report()))

        assertEquals(4, DeviceCheckId.entries.size)
        for (id in DeviceCheckId.entries) {
            composeRule.onNodeWithText(id.title).assertIsDisplayed()
        }
    }
}