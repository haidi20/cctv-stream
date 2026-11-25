package id.warung.monitor.feature.home.domain

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun profile(
    id: String = "cam-1",
    label: String = "Kamera Utama",
    source: CameraSource = CameraSource.Demo,
): CameraProfile = CameraProfile(
    id = CameraId(id),
    label = CameraLabel(label),
    source = source,
    createdAt = 1L,
    updatedAt = 1L,
)

internal class RecordingPreview : CameraPreviewCoordinator {
    val opened = mutableListOf<String>()
    private val mutableState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    override val state: StateFlow<PreviewState> = mutableState

    override suspend fun open(camera: CameraProfile, secret: CharArray?) {
        opened += camera.id.value
        mutableState.value = PreviewState.Playing
    }

    override suspend fun close() {
        mutableState.value = PreviewState.Idle
    }

    fun fail(kind: StreamErrorKind) {
        mutableState.value = PreviewState.Failed(kind)
    }
}

internal class MutableStatusSource(
    var status: EngineStatus = EngineStatus.Unavailable,
) : EngineStatusSource {
    var reads = 0

    override suspend fun read(camera: CameraProfile?): EngineStatus {
        reads += 1
        return status
    }
}

internal fun status(
    camera: String = "cam-1",
    phase: EnginePhase = EnginePhase.Live,
    available: Int = 3,
    occupied: Int = 2,
    unknown: Int = 1,
    modelReady: Boolean = true,
    framesIn: Long = 30L,
): EngineStatus = EngineStatus(
    available = true,
    cameraId = camera,
    phase = phase,
    framesIn = framesIn,
    tables = available + occupied + unknown,
    availableCount = available,
    occupiedCount = occupied,
    unknownCount = unknown,
    modelReady = modelReady,
    demo = false,
)