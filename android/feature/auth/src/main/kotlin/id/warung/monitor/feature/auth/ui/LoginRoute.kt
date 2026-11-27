package id.warung.monitor.feature.auth.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.warung.monitor.feature.auth.domain.BiometricUnlocker
import id.warung.monitor.feature.auth.domain.LoginState
import id.warung.monitor.feature.auth.domain.LoginViewModel

/**
 * Stateful login entry point; unlock is a one-shot event, not persisted state.
 *
 * [biometricUnlocker] is optional. When it is `null`, or the device has no
 * enrolled sensor, the keypad hides the "ID" key and the PIN is the only path.
 * One automatic attempt runs per screen entry, after the credential load
 * settles, matching `android-app-plan.md` 3.2. Any failure leaves the keypad
 * usable and does not consume a PIN attempt.
 */
@Composable
fun LoginRoute(
    onUnlocked: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    biometricUnlocker: BiometricUnlocker? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.unlocked.collect { onUnlocked() }
    }

    val unlocker = remember(biometricUnlocker) {
        biometricUnlocker ?: BiometricUnlocker(null)
    }
    DisposableEffect(unlocker) {
        onDispose { unlocker.cancel() }
    }
    var autoAttempted by remember(unlocker) { mutableStateOf(false) }
    LaunchedEffect(unlocker, state) {
        if (!autoAttempted && unlocker.available && state is LoginState.EnterPin) {
            autoAttempted = true
            unlocker.request(onUnlocked)
        }
    }

    LoginScreen(
        state = state,
        onDigit = viewModel::appendDigit,
        onDelete = viewModel::deleteDigit,
        onBiometric = if (unlocker.available) {
            { unlocker.request(onUnlocked) }
        } else {
            null
        },
        onReset = viewModel::resetApp,
        modifier = modifier,
        onStartChangePin = viewModel::startPinChange,
        onCancelChangePin = viewModel::cancelPinChange,
        onStartResetPin = viewModel::startResetPin,
        onCancelResetPin = viewModel::cancelResetPin,
    )
}
