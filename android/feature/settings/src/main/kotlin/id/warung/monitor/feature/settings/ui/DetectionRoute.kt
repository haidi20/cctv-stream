package id.warung.monitor.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.warung.monitor.feature.settings.domain.DetectionViewModel

/**
 * Stateful entry point for the detection preset screen.
 *
 * [onSaved] lets `app` react after the write; today it only refreshes the
 * schedule-free value, but the seam exists so a future session restart does not
 * have to reach into the view model.
 */
@Composable
fun DetectionRoute(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DetectionScreen(
        state = state,
        onSelect = viewModel::select,
        onSave = { viewModel.save(onSaved) },
        onDismissMessage = viewModel::dismissMessage,
        onBack = onBack,
        modifier = modifier,
    )
}
