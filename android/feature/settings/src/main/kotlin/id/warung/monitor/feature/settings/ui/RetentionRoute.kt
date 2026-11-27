package id.warung.monitor.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.warung.monitor.feature.settings.domain.RetentionViewModel

/**
 * Stateful entry point for "Penyimpanan & Riwayat".
 *
 * [onSaved] lets `app` reschedule the prune worker; the screen itself never
 * deletes anything (`docs/android-sync.md` section 7).
 */
@Composable
fun RetentionRoute(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RetentionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RetentionScreen(
        state = state,
        onSelect = viewModel::select,
        onSave = { viewModel.save(onSaved) },
        onDismissMessage = viewModel::dismissMessage,
        onBack = onBack,
        modifier = modifier,
    )
}
