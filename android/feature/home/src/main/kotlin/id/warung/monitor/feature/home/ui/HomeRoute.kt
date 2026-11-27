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
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    preview: @Composable (CameraProfile) -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    powerMode: id.warung.monitor.feature.home.domain.HomePowerMode =
        id.warung.monitor.feature.home.domain.HomePowerMode.Standard,
    appVersion: String = "",
    devMode: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
    scanViewModel: id.warung.monitor.feature.cameras.ui.CameraScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scanState by scanViewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        scanState = scanState,
        onScan = {
            viewModel.openScan()
            scanViewModel.start()
        },
        onRetryScan = scanViewModel::start,
        onOpenCamera = { id: CameraId ->
            viewModel.selectCamera(id)
            viewModel.dismissScan()
            scanViewModel.dismissResults()
        },
        onDismissScan = {
            viewModel.dismissScan()
            scanViewModel.cancel()
        },
        onManageCameras = onManageCameras,
        onOpenSettings = onOpenSettings,
        onLogout = onLogout,
        onRetry = viewModel::retry,
        preview = preview,
        modifier = modifier,
        powerMode = powerMode,
        appVersion = appVersion,
        devMode = devMode,
        onMonitoringChange = viewModel::setMonitoringEnabled,
        onReconnect = viewModel::reconnect,
        onAnalysisChange = viewModel::setAnalysisEnabled,
    )
}
