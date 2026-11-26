package id.warung.monitor.feature.onboarding.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.warung.monitor.feature.onboarding.domain.DeviceProfile
import id.warung.monitor.feature.onboarding.domain.OnboardingState
import id.warung.monitor.feature.onboarding.domain.OnboardingViewModel

/**
 * Stateful diagnostic entry point.
 *
 * [onApproved] fires for a profile the user may use. A blocked report never
 * calls it, so Home and the engine service stay shut; [onCloseApp] is the only
 * exit. Approval is persisted state, not a one-shot event, so the effect is
 * re-armed after process death and rotation.
 */
@Composable
fun DeviceCheckRoute(
    onApproved: (DeviceProfile) -> Unit,
    onCloseApp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    val approved = state as? OnboardingState.Approved
    LaunchedEffect(approved) {
        if (approved != null) onApproved(approved.profile)
    }

    DeviceCheckScreen(
        state = toUiState(state),
        checking = state is OnboardingState.Checking,
        onRunCheck = viewModel::runCheck,
        onContinue = viewModel::continueWithLowerProfile,
        onRetry = viewModel::retry,
        onCopyReport = {
            val report = (state as? OnboardingState.Blocked)?.report
                ?: (state as? OnboardingState.Ready)?.report
            if (report != null && clipboard != null) {
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Laporan perangkat", report.toClipboardText()),
                )
            }
        },
        onCloseApp = onCloseApp,
        modifier = modifier,
    )
}