package id.warung.monitor.feature.onboarding.ui

import id.warung.monitor.feature.onboarding.domain.CheckStatus
import id.warung.monitor.feature.onboarding.domain.DeviceCheckId
import id.warung.monitor.feature.onboarding.domain.DeviceCheckRow
import id.warung.monitor.feature.onboarding.domain.DeviceErrorCode
import id.warung.monitor.feature.onboarding.domain.DeviceProfile
import id.warung.monitor.feature.onboarding.domain.DeviceReport
import id.warung.monitor.feature.onboarding.domain.OnboardingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Pure mapping test; no Compose host or Android runtime is needed. */
class OnboardingUiStateTest {

    private fun report(profile: DeviceProfile) = DeviceReport(
        profile = profile,
        rows = listOf(
            DeviceCheckRow(DeviceCheckId.Platform, CheckStatus.Pass, "Android 13"),
            DeviceCheckRow(DeviceCheckId.Memory, CheckStatus.Pass, "RAM 4096 MB"),
            DeviceCheckRow(DeviceCheckId.Engine, CheckStatus.Skipped, "Model belum dipasang"),
            DeviceCheckRow(DeviceCheckId.Storage, CheckStatus.Pass, "2048 MB bebas"),
        ),
        engineReady = false,
        blockedCode = if (profile == DeviceProfile.Blocked) DeviceErrorCode.ABI_UNSUPPORTED else null,
        appVersionCode = 1,
        appVersionName = "0.1.0",
    )

    @Test
    fun loading_and_checking_stay_on_a_spinner_or_the_intro() {
        assertSame(OnboardingUiState.Loading, toUiState(OnboardingState.Loading))
        assertSame(OnboardingUiState.Check, toUiState(OnboardingState.Check))
        assertSame(OnboardingUiState.Check, toUiState(OnboardingState.Checking))
    }

    @Test
    fun ready_and_blocked_both_draw_the_report() {
        val ready = report(DeviceProfile.Lite)
        val blocked = report(DeviceProfile.Blocked)

        assertEquals(ready, (toUiState(OnboardingState.Ready(ready)) as OnboardingUiState.ReportBody).report)
        assertEquals(blocked, (toUiState(OnboardingState.Blocked(blocked)) as OnboardingUiState.ReportBody).report)
    }

    @Test
    fun approved_hands_over_to_the_shell_instead_of_drawing_a_body() {
        assertSame(OnboardingUiState.Loading, toUiState(OnboardingState.Approved(DeviceProfile.Full)))
    }
}