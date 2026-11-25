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
    val loading: Boolean = true,
) {
    val hasCamera: Boolean get() = camera != null
}

sealed interface HomeOverlay {
    data object None : HomeOverlay
    data object Scan : HomeOverlay
}

/** Preview + detection state, all owned outside the composables. */
sealed interface StreamUiState {
    data object Idle : StreamUiState
    data object Connecting : StreamUiState
    data class Live(val sinceMillis: Long) : StreamUiState
    data class Reconnecting(val attempt: Int) : StreamUiState
    data class Failed(val kind: StreamErrorKind) : StreamUiState
}

enum class StreamErrorKind {
    Unreachable,
    Timeout,
    AuthFailed,
    Protocol,
    Closed,
    Unknown;

    val message: String
        get() = when (this) {
            Unreachable -> "Kamera tidak terjangkau"
            Timeout -> "Kamera tidak menjawab"
            AuthFailed -> "Kata sandi kamera salah"
            Protocol -> "Format stream tidak didukung"
            Closed -> "Kamera menutup koneksi"
            Unknown -> "Stream gagal"
        }
}

/** Snapshot read from the Rust engine; no Android types cross this seam. */
data class EngineStatus(
    val available: Boolean,
    val cameraId: String?,
    val phase: EnginePhase,
    val framesIn: Long,
    val tables: Int,
    val availableCount: Int,
    val occupiedCount: Int,
    val unknownCount: Int,
    val modelReady: Boolean,
    val demo: Boolean,
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

/** Clock seam so JVM tests control "diperbarui HH:mm:ss". */
fun interface HomeClock {
    fun nowMillis(): Long
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