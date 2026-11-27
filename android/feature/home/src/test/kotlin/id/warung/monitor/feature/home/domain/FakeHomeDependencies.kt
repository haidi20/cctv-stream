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
    monitoringEnabled: Boolean = true,
): CameraProfile = CameraProfile(
    id = CameraId(id),
    label = CameraLabel(label),
    source = source,
    createdAt = 1L,
    updatedAt = 1L,
    monitoringEnabled = monitoringEnabled,
)

internal class RecordingPreview : CameraPreviewCoordinator {
    val opened = mutableListOf<String>()
    val secrets = mutableListOf<CharArray?>()
    private val mutableState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    override val state: StateFlow<PreviewState> = mutableState

    /**
     * `false` keeps the session in `Connecting` after [open] so a test can
     * fail it again without pretending the stream ever became live.
     */
    var autoPlayOnOpen: Boolean = true

    override suspend fun open(camera: CameraProfile, secret: CharArray?) {
        opened += camera.id.value
        secrets += secret?.copyOf()
        mutableState.value = if (autoPlayOnOpen) PreviewState.Playing else PreviewState.Connecting
    }

    override suspend fun close() {
        mutableState.value = PreviewState.Idle
    }

    fun fail(kind: StreamErrorKind) {
        mutableState.value = PreviewState.Failed(kind)
    }
}

/** Records which failures Home told the engine about. */
internal class RecordingFailureReporter : StreamFailureReporter {
    val reported = mutableListOf<StreamErrorKind>()

    override suspend fun report(kind: StreamErrorKind) {
        reported += kind
    }
}

/** Records what Home told the notification layer, without starting a service. */
internal class RecordingMonitoringSignal : MonitoringSignal {
    val samples = mutableListOf<MonitoringSample>()

    override fun publish(sample: MonitoringSample) {
        samples += sample
    }
}

/** Manual transport stream so tests can simulate Wi-Fi dropping and returning. */
internal class MutableNetworkMonitor(
    initial: NetworkReach = NetworkReach.WifiPrivate,
) : NetworkMonitor {
    private val state = MutableStateFlow(initial)
    val current: NetworkReach get() = state.value

    fun emit(reach: NetworkReach) {
        state.value = reach
    }

    override fun reach(): kotlinx.coroutines.flow.Flow<NetworkReach> = state
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

/** Scripted analysis read; `null` means "no session detail". */
internal class MutableAnalysisSource(
    var analysis: EngineAnalysis? = null,
) : EngineAnalysisSource {
    var reads = 0

    override suspend fun readAnalysis(camera: CameraProfile?): EngineAnalysis? {
        reads += 1
        return analysis
    }
}

internal fun analysis(
    tables: List<AnalysisTable> = listOf(
        AnalysisTable("meja-01", "Meja 1", AnalysisStatus.Available, 0, 0.0f, 0L),
        AnalysisTable("meja-02", "Meja 2", AnalysisStatus.Occupied, 2, 0.8f, 0L),
        AnalysisTable("meja-03", "Meja 3", AnalysisStatus.Unknown, 0, 0.0f, 0L),
    ),
    detections: List<DetectionRect> = listOf(
        DetectionRect(0.1f, 0.2f, 0.3f, 0.4f, 0.9f),
        DetectionRect(0.5f, 0.1f, 0.2f, 0.3f, 0.7f),
    ),
    inferenceFps: Double = 2.0,
    lastAnalysisAgeMillis: Long? = 500L,
    frameToInferenceMillis: Long? = 120L,
): EngineAnalysis = EngineAnalysis(
    tables = tables,
    detections = detections,
    inferenceFps = inferenceFps,
    lastAnalysisAgeMillis = lastAnalysisAgeMillis,
    frameToInferenceMillis = frameToInferenceMillis,
)

/** Manual engine-change signal so a test decides exactly when Rust "speaks". */
internal class MutableEngineChangeSignal : EngineChangeSignal {
    private val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    override fun changes(): kotlinx.coroutines.flow.Flow<Unit> = changes

    fun emit() {
        changes.tryEmit(Unit)
    }
}

internal fun status(
    camera: String = "cam-1",
    phase: EnginePhase = EnginePhase.Live,
    failure: StreamErrorKind? = null,
    needsRecalibration: Boolean = false,
    available: Int = 3,
    occupied: Int = 2,
    unknown: Int = 1,
    modelReady: Boolean = true,
    framesIn: Long = 30L,
): EngineStatus = EngineStatus(
    available = true,
    cameraId = camera,
    phase = phase,
    failure = failure,
    needsRecalibration = needsRecalibration,
    framesIn = framesIn,
    tables = available + occupied + unknown,
    availableCount = available,
    occupiedCount = occupied,
    unknownCount = unknown,
    modelReady = modelReady,
    demo = false,
)


/** In-memory master monitoring switch; the default is "on". */
internal class FakeMonitoringPreference(
    initial: Boolean = true,
) : MonitoringPreference {
    private val state = MutableStateFlow(initial)

    val value: Boolean get() = state.value

    fun emit(enabled: Boolean) {
        state.value = enabled
    }

    override fun observe(): kotlinx.coroutines.flow.Flow<Boolean> = state

    override suspend fun set(enabled: Boolean) {
        state.value = enabled
    }
}
