package id.warung.monitor.feature.cameras.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.warung.monitor.core.model.CameraProfile

/**
 * Stateful entry point. Opening a camera is delegated to the app shell because
 * only it owns the single Rust engine session.
 *
 * The shell receives the full profile, not just the id: the mapping screen shows
 * the user-visible label while every stored row still uses the stable id.
 */
@Composable
fun CameraListRoute(
    onOpenCamera: (CameraProfile) -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    viewModel: CameraListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CameraListScreen(
        state = state,
        onAdd = viewModel::startAdd,
        onEdit = viewModel::startEdit,
        onDelete = viewModel::requestDelete,
        onOpen = { cameraId ->
            val profile = state.cameras.firstOrNull { it.id == cameraId }
            if (profile != null) {
                viewModel.markOpened(cameraId)
                onOpenCamera(profile)
            }
        },
        onDismissEditor = viewModel::dismissEditor,
        onEditorLabelChange = viewModel::updateLabel,
        onEditorSourceChange = viewModel::updateSourceType,
        onEditorHostChange = viewModel::updateHost,
        onEditorPortChange = viewModel::updatePort,
        onEditorPathChange = viewModel::updatePath,
        onEditorUsernameChange = viewModel::updateUsername,
        onEditorSave = viewModel::saveEditor,
        onDismissDelete = viewModel::dismissDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onDismissMessage = viewModel::dismissMessage,
        modifier = modifier,
    )
}