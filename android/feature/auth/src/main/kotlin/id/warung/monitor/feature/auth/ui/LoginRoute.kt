package id.warung.monitor.feature.auth.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.warung.monitor.feature.auth.domain.LoginViewModel

/** Stateful login entry point; unlock is a one-shot event, not persisted state. */
@Composable
fun LoginRoute(
    onUnlocked: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.unlocked.collect { onUnlocked() }
    }
    LoginScreen(
        state = state,
        onDigit = viewModel::appendDigit,
        onDelete = viewModel::deleteDigit,
        onBiometric = null,
        onReset = viewModel::resetApp,
        modifier = modifier,
    )
}