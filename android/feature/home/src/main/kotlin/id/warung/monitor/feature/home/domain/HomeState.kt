package id.warung.monitor.feature.home.domain

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.TableSummary

/** Everything the Home screen draws. */
data class HomeUiState(
    val cameras: List<CameraProfile> = emptyList(),
    val camera: CameraProfile? = null,
    val stream: StreamUiState = StreamUiState.Idle,
    val summary: TableSummary? = null,
    val summaryStale: Boolean = false,
    val lastUpdatedMillis: Long? = null,
    val overlay: HomeOverlay = HomeOverlay.None,
    val banner: String? = null,
    val demoMode: Boolean = false,
    val modelReady: Boolean = false,
    /**
     * One row per configured table, in table-id order. `null` before the
     * engine has a configuration; an empty list means the map exists but has
     * no tables. Rust owns the status; Home only renders it.
     */
    val tables: List<AnalysisTable>? = null,
    /**
     * Person boxes from the last analysed frame, normalised to 0..1.
     * Cleared when the stream dies so a dead camera never leaves stale boxes.
     */
    val detections: List<DetectionRect> = emptyList(),
    /**
     * Requested inference rate from Rust; 0.0 before a session starts.
     */
    val inferenceFps: Double = 0.0,
    /**
     * Millis since the last inference attempt, or `null` when none has run.
     */
    val lastAnalysisAgeMillis: Long? = null,
    /**
     * Wall-clock time of the last status change, for the `dianalisis HH:mm:ss`
     * line. `null` before any analysis.
     */
    val lastAnalysisMillis: Long? = null,
    /** Full local date and time of the analysed frame, for the loading caption. */
    val analysisFrameAtMillis: Long? = null,
    /** Delay from frame capture to inference, owned by Rust. */
    val frameToInferenceMillis: Long? = null,
    /**
     * Rust no longer recognises the live view as the one the map was drawn for:
     * the video aspect ratio changed, or the camera itself was panned/tilted.
     * The polygons are not moved automatically; the user must redo them.
     */
    val needsRecalibration: Boolean = false,
    /**
     * How much of Rust's view grid differs from the calibration frame,
     * `0.0..1.0`, or `null` when no comparison has run.
     *
     * Only meaningful together with [needsRecalibration]; the banner uses it to
     * say whether the view drifted a little (lighting) or a lot (the camera was
     * knocked), which is the difference between "wait and see" and "redo the
     * map now".
     */
    val viewChangedFraction: Float? = null,
    /**
     * Whether the selected camera already has polygons saved in Room.
     *
     * Analysis is only meaningful after a map exists: without polygons Rust has
     * nothing to test a person box against. [TableMapState.Unknown] never
     * blocks the panel (no source is wired, or the first read has not landed),
     * so a missing repository can never fake "belum ada peta".
     */
    val mapping: TableMapState = TableMapState.Unknown,
    /**
     * Coarse transport view for the offline banner. [NetworkReach.WifiPrivate]
     * is the only state in which a local camera can be reached; cellular and
     * offline both mean "not on the warung Wi-Fi yet".
     */
    val network: NetworkReach = NetworkReach.WifiPrivate,
    /**
     * `true` for a short window after the phone regains the warung Wi-Fi, so
     * the UI can show a transient "Anda kembali online" note. The ViewModel
     * clears it after [HomeViewModel.RECONNECTED_VISIBLE_MS]; the UI never
     * schedules the timer itself.
     */
    val justReconnected: Boolean = false,
    /**
     * Master monitoring switch, persisted outside Room because it applies to
     * the whole app. `false` hides every camera list and never opens a stream;
     * turning it back on restores the saved cameras as they were.
     */
    val monitoringEnabled: Boolean = true,
    /** Whether the analysis worker is currently allowed to infer. */
    val analysisEnabled: Boolean = true,
    val loading: Boolean = true,
    /** True while a user-triggered reconnect is in flight; stops click spam. */
    val reconnecting: Boolean = false,
) {
    val hasCamera: Boolean get() = camera != null

    /**
     * `true` only when Room positively reports that this camera has no saved
     * polygons. An unwired or still-loading source stays `false`, so the UI
     * fails open instead of telling the user to redraw a map that may exist.
     */
    val needsMapping: Boolean get() = mapping is TableMapState.Missing

    /**
     * Cameras that may actually stream: the master switch is on and the
     * camera itself is not paused. The scan sheet reads this list, so a paused
     * camera disappears exactly like the requirement asks.
     */
    val monitoringCameras: List<CameraProfile>
        get() = if (!monitoringEnabled) emptyList() else cameras.filter { it.monitoringEnabled }
}

sealed interface HomeOverlay {
    data object None : HomeOverlay
    data object Scan : HomeOverlay
}

/** One table as Rust reports it; Kotlin never re-derives the status. */
data class AnalysisTable(
    val id: String,
    val label: String,
    val status: AnalysisStatus,
    val persons: Int,
    val confidence: Float,
    val sinceMillis: Long,
)

/** Mirrors Rust `TableStatus`; `Unknown` is never folded into `Available`. */
enum class AnalysisStatus {
    Available,
    Occupied,
    Unknown,
}

/** One normalised person box, ready for a Compose overlay. */
data class DetectionRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
)

/** Preview + detection state, all owned outside the composables. */
sealed interface StreamUiState {
    data object Idle : StreamUiState
    data object Connecting : StreamUiState
    data class Live(val sinceMillis: Long) : StreamUiState
    /** [nextRetryInMs] is the scheduled wait, so the UI never has to guess. */
    data class Reconnecting(val attempt: Int, val nextRetryInMs: Long) : StreamUiState
    data class Failed(val kind: StreamErrorKind) : StreamUiState
}

/**
 * Why a preview/stream is not live.
 *
 * [NoNetwork] and [WrongNetwork] are separate cases on purpose: an unseen table
 * must never be counted as empty, and "Wi-Fi mati" is not "kamera rusak"
 * (`network-topology.md` §7).
 */
enum class StreamErrorKind {
    NoNetwork,
    WrongNetwork,
    Unreachable,
    Timeout,
    AuthFailed,
    Protocol,
    Closed,
    Unknown;

    val message: String
        get() = when (this) {
            NoNetwork -> "Wi-Fi belum tersambung"
            WrongNetwork -> "Pindah ke Wi-Fi warung"
            Unreachable -> "Kamera tidak terjangkau"
            Timeout -> "Kamera tidak menjawab"
            AuthFailed -> "Kata sandi kamera salah"
            Protocol -> "Format stream tidak didukung"
            Closed -> "Kamera menutup koneksi"
            Unknown -> "Stream gagal"
        }

    /** `true` when retrying cannot help until the user changes networks. */
    val isNetworkProblem: Boolean
        get() = this == NoNetwork || this == WrongNetwork
}

/** Coarse network view, owned by the app shell. */
enum class NetworkReach { WifiPrivate, CellularOnly, Offline }

/**
 * Persisted master monitoring switch.
 *
 * Kept as a narrow seam so Home stays JVM-testable and the app shell can back
 * it with DataStore. A missing implementation defaults to "on".
 */
interface MonitoringPreference {
    /** Emits the stored value once, then on every change. */
    fun observe(): kotlinx.coroutines.flow.Flow<Boolean>

    suspend fun set(enabled: Boolean)
}

fun interface NetworkMonitor {
    /** Emits on every transport change; must not block. */
    fun reach(): kotlinx.coroutines.flow.Flow<NetworkReach>
}

/** Snapshot read from the Rust engine; no Android types cross this seam. */
data class EngineStatus(
    val available: Boolean,
    val cameraId: String?,
    val phase: EnginePhase,
    /**
     * Why the engine reports a failure, when it knows. Rust distinguishes
     * `auth_failed` from `timeout` and `unreachable`; collapsing all of them to
     * `Unknown` would tell the user to check the network when the password is
     * wrong.
     */
    val failure: StreamErrorKind? = null,
    /** True when the stream aspect ratio no longer matches the saved map. */
    val needsRecalibration: Boolean = false,
    /**
     * Fraction of Rust's view grid that differs from the calibration frame,
     * `0.0..1.0`, or `null` before the first compared frame.
     *
     * This is the measurement behind [needsRecalibration]: a few percent is
     * lighting, most of the grid is a camera that was knocked. Showing the
     * number lets a user (and a support report) tell those apart instead of
     * guessing from a binary banner.
     */
    val viewChangedFraction: Float? = null,
    val framesIn: Long,
    val tables: Int,
    val availableCount: Int,
    val occupiedCount: Int,
    val unknownCount: Int,
    val modelReady: Boolean,
    val demo: Boolean,
    /**
     * Resident set size of the Rust process in KiB, or `null` when the platform
     * hides `/proc`. Feeds `runtime.rss_kb` in the heartbeat so a soak can tell
     * a native leak from a Compose one.
     */
    val rssKb: Long? = null,
    /** Frames skipped by the rate sampler; backpressure, never a queue. */
    val framesDropped: Long? = null,
    /** p50 inference latency in ms, measured by Rust. */
    val inferenceP50Ms: Long? = null,
    /** p95 inference latency in ms, measured by Rust. */
    val inferenceP95Ms: Long? = null,
) {
    val summary: TableSummary?
        get() = if (tables > 0) {
            TableSummary(availableCount, occupiedCount, unknownCount)
        } else {
            null
        }

    companion object {
        val Unavailable: EngineStatus = EngineStatus(
            available = false,
            cameraId = null,
            phase = EnginePhase.Idle,
            failure = null,
            needsRecalibration = false,
            framesIn = 0L,
            tables = 0,
            availableCount = 0,
            occupiedCount = 0,
            unknownCount = 0,
            modelReady = false,
            demo = false,
        )
    }
}

enum class EnginePhase { Idle, Connecting, Live, Failed }

/** Read by HomeViewModel on a short poll; implementations must not block. */
fun interface EngineStatusSource {
    suspend fun read(camera: CameraProfile?): EngineStatus
}

/**
 * Optional second read for the analysis panel: per-table status and person
 * boxes owned by Rust.
 *
 * Kept separate from [EngineStatusSource] so the aggregate totals keep their
 * cheap poll while the detail read can be skipped or extended independently.
 * Returning `null` means "no session"; an empty table list means the map is
 * configured but empty. A missing implementation leaves the panel hidden.
 */
fun interface EngineAnalysisSource {
    suspend fun readAnalysis(camera: CameraProfile?): EngineAnalysis?
}

/** Rust-owned detail for one analysed frame. */
data class EngineAnalysis(
    val tables: List<AnalysisTable>,
    val detections: List<DetectionRect>,
    val inferenceFps: Double,
    val lastAnalysisAgeMillis: Long?,
    /** Newest frame -> inference start delay reported by Rust. */
    val frameToInferenceMillis: Long? = null,
)

/**
 * Saved-map presence for the selected camera.
 *
 * Counted from Room polygons, never from an engine status: a fresh camera with
 * an open stream but no map must ask for a map instead of showing an empty
 * analysis panel. Rust stays the only owner of table *status*.
 */
sealed interface TableMapState {
    /** No source wired yet, or its first emission has not arrived. */
    data object Unknown : TableMapState

    /** Room holds no usable polygon set for this camera. */
    data object Missing : TableMapState

    /** At least one polygon is saved; [revision] lets the UI notice a redo. */
    data class Mapped(val tables: Int, val revision: Long) : TableMapState
}

/** Map summary read from Room; contains no geometry and no status. */
data class SavedTableMap(
    val tables: Int,
    val revision: Long,
)

/**
 * Reads the saved map for one camera.
 *
 * Implementations must emit once immediately and then on every change, and
 * must not block: Home collects this per selected camera. Emitting `null`
 * means "no saved map"; a read error is reported the same way because the user
 * can always redraw the map.
 */
fun interface TableMappingSource {
    fun observe(cameraId: CameraId): kotlinx.coroutines.flow.Flow<SavedTableMap?>
}

/**
 * Coalesced "the Rust engine changed" signal.
 *
 * An emission only means "re-read [EngineStatusSource]"; it carries no
 * snapshot, may be dropped, and must never be treated as a count. Collecting
 * the flow is what registers the native listener, so an implementation may do
 * bounded setup work before its first emission. A missing engine must not fail
 * the flow; polling remains the fallback when no event can ever arrive.
 */
fun interface EngineChangeSignal {
    fun changes(): kotlinx.coroutines.flow.Flow<Unit>
}

/** Preview state owned by the app-level player, not by a composable. */
sealed interface PreviewState {
    data object Idle : PreviewState
    data object Connecting : PreviewState
    data object Playing : PreviewState
    data class Failed(val kind: StreamErrorKind) : PreviewState
}

/**
 * Opens exactly one camera preview.
 *
 * `secret` is a mutable char array owned by the caller and cleared by the
 * implementation after use. It must never be persisted or logged.
 */
interface CameraPreviewCoordinator {
    val state: kotlinx.coroutines.flow.StateFlow<PreviewState>

    suspend fun open(camera: CameraProfile, secret: CharArray?)

    suspend fun close()
}

/**
 * Reads the Keystore secret for a camera without exposing it to UI state.
 * Returning `null` is legal: a camera with no password still opens, and a
 * missing implementation keeps every existing fake/test flow working.
 */
fun interface CameraSecretReader {
    suspend fun read(id: CameraId): CharArray?
}

/** Clock seam so JVM tests control "diperbarui HH:mm:ss". */
fun interface HomeClock {
    fun nowMillis(): Long
}

/**
 * Detection cadence Home should advertise.
 *
 * The engine applies the profile; Home only renders an honest label so a
 * `lite` device is not mistaken for a full one. Kept in this module so Home
 * never imports the onboarding feature.
 */
enum class HomePowerMode {
    Standard,
    Saver,
}

/**
 * Engine poll cadence. A value <= 0 disables polling, which keeps JVM tests
 * from waiting forever on a virtual-time loop.
 */
class HomePollInterval(val millis: Long) {
    init {
        require(millis <= MAX_MILLIS) { "Poll interval is too large" }
    }

    companion object {
        const val DEFAULT_MILLIS: Long = 1_000L
        const val MAX_MILLIS: Long = 60_000L
    }
}
