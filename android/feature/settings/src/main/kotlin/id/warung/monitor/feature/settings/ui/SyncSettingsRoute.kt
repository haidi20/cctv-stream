package id.warung.monitor.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.warung.monitor.feature.settings.domain.SyncSettingsViewModel

/**
 * Stateful entry point for the optional VPS settings.
 *
 * [onSaved] fires after a successful save so `app` can restart the worker
 * immediately; it is a plain callback, not a Flow event, because the form is
 * already the owner of the save result.
 */
@Composable
fun SyncSettingsRoute(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onOpenRetention: () -> Unit,
    onOpenDetection: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SyncSettingsScreen(
        state = state,
        onBaseUrlChange = viewModel::updateBaseUrl,
        onTokenChange = viewModel::updateToken,
        onSave = { viewModel.save(onSaved) },
        onClearToken = viewModel::clearToken,
        onOpenRetention = onOpenRetention,
        onOpenDetection = onOpenDetection,
        onDismissMessage = viewModel::dismissMessage,
        onBack = onBack,
        modifier = modifier,
    )
}
