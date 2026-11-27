package id.warung.monitor.feature.cameras.ui

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.network.ConnectionReport

/** Everything the camera list and its editor dialog draw. */
data class CameraListState(
    val cameras: List<CameraProfile>,
    val editing: CameraEditState?,
    val pendingDelete: CameraProfile?,
    val message: String?,
    val isLoading: Boolean,
) {
    val isEmpty: Boolean get() = !isLoading && cameras.isEmpty()

    companion object {
        val Initial: CameraListState = CameraListState(
            cameras = emptyList(),
            editing = null,
            pendingDelete = null,
            message = null,
            isLoading = true,
        )
    }
}

/** Draft for the add/edit dialog; never persisted until the user confirms. */
data class CameraEditState(
    val cameraId: CameraId?,
    val label: String,
    val sourceType: CameraSourceType,
    val host: String,
    val port: String,
    val streamPath: String,
    val username: String,
    val error: String? = null,
    /**
     * Typed only for "Tes koneksi". It is never written to Room, DataStore, or
     * a log; the sheet clears it when the editor closes.
     */
    val password: String = "",
    val connection: ConnectionTestUiState = ConnectionTestUiState.Idle,
) {
    val isNew: Boolean get() = cameraId == null
    val title: String get() = if (isNew) "Tambah kamera" else "Ubah kamera"

    companion object {
        val New: CameraEditState = CameraEditState(
            cameraId = null,
            label = "",
            sourceType = CameraSourceType.Demo,
            host = "",
            port = "554",
            streamPath = "/h264/ch1/sub/av_stream",
            username = "",
        )

        fun from(profile: CameraProfile): CameraEditState = when (val source = profile.source) {
            // Demo has no fields at all, and a dev-only video camera is never
            // opened here (`CameraListViewModel.startEdit` refuses it), so both
            // share the placeholder draft.
            id.warung.monitor.core.model.CameraSource.Demo,
            is id.warung.monitor.core.model.CameraSource.Video -> CameraEditState(
                cameraId = profile.id,
                label = profile.label.value,
                sourceType = CameraSourceType.Demo,
                host = "",
                port = "554",
                streamPath = "/h264/ch1/sub/av_stream",
                username = "",
            )
            is id.warung.monitor.core.model.CameraSource.Rtsp -> CameraEditState(
                cameraId = profile.id,
                label = profile.label.value,
                sourceType = CameraSourceType.Rtsp,
                host = source.host,
                port = source.port.toString(),
                streamPath = source.path,
                username = source.username.orEmpty(),
            )
        }
    }
}

/** Form choice; the model layer still validates every value. */
enum class CameraSourceType(val label: String) {
    Demo("Demo"),
    Rtsp("RTSP"),
}

/**
 * State of the manual four-layer connection test.
 *
 * [ConnectionTestUiState.Done] keeps the full report so the sheet can show a
 * green row for each layer that passed, not only the first failure.
 */
sealed interface ConnectionTestUiState {
    data object Idle : ConnectionTestUiState
    data object Running : ConnectionTestUiState
    data class Done(val report: ConnectionReport) : ConnectionTestUiState
}