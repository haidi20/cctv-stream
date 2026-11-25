package id.warung.monitor.feature.tables.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.warung.monitor.core.model.MappingFrame

/** Stateful entry point used by the app shell (and later by Nav3). */
@Composable
fun TableMappingRoute(
    cameraId: String,
    cameraLabel: String,
    onBack: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    preview: (@Composable (onVideoSize: (Int, Int) -> Unit, onSnapshot: (ImageBitmap) -> Unit) -> Unit)? = null,
    viewModel: TableMappingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(cameraId, cameraLabel) {
        viewModel.bindCamera(cameraId, cameraLabel)
    }
    TableMappingScreen(
        state = state,
        onBack = onBack,
        onAddTable = viewModel::addTable,
        onFinishDrawing = viewModel::finishDrawing,
        onCancelDrawing = viewModel::cancelDrawing,
        onSelectTable = viewModel::selectTable,
        onSelectVertex = viewModel::selectVertex,
        onAddPoint = viewModel::addVertex,
        onMoveVertex = viewModel::moveVertex,
        onRemoveVertex = viewModel::removeVertex,
        onRemoveTable = viewModel::deleteTable,
        onLabelChange = viewModel::updateLabel,
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onSave = viewModel::save,
        onResetMap = viewModel::resetMap,
        onRetryEngine = viewModel::retryEngine,
        onDismissMessage = viewModel::dismissMessage,
        modifier = modifier,
        preview = preview?.let { slot ->
            {
                slot(
                    { width, height ->
                        runCatching {
                            viewModel.setLiveFrame(
                                width,
                                height,
                                MappingFrame.canonicalAspectRatio(width, height),
                            )
                        }
                    },
                    viewModel::setSnapshot,
                )
            }
        },
        onClearSnapshot = viewModel::clearSnapshot,
    )
}