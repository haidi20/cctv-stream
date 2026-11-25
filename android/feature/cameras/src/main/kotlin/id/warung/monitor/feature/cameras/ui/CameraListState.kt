package id.warung.monitor.feature.cameras.ui

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraProfile

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
            id.warung.monitor.core.model.CameraSource.Demo -> CameraEditState(
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