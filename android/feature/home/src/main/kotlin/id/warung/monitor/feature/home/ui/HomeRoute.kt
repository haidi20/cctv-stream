package id.warung.monitor.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.feature.home.domain.HomeViewModel

/**
 * Stateful Home entry point.
 *
 * [preview] is supplied by `app` so this feature never imports Media3. Keeping
 * the player outside the module also keeps the Home tests JVM-only.
 */
@Composable
fun HomeRoute(
    onManageCameras: () -> Unit,
    onLogout: () -> Unit,
    preview: @Composable (CameraProfile) -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onScan = viewModel::openScan,
        onOpenCamera = { id: CameraId ->
            viewModel.selectCamera(id)
            viewModel.dismissScan()
        },
        onDismissScan = viewModel::dismissScan,
        onManageCameras = onManageCameras,
        onLogout = onLogout,
        onRetry = viewModel::retry,
        preview = preview,
        modifier = modifier,
    )
}