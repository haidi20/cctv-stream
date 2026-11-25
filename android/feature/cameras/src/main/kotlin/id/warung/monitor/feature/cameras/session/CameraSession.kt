package id.warung.monitor.feature.cameras.session

import id.warung.monitor.core.model.CameraId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One camera at a time, by design.
 *
 * The user explicitly opens a camera; opening another one must close the first
 * before the new session becomes active. Implementations own the engine and
 * preview lifecycle; this interface only exposes the contract the UI needs.
 */
interface CameraSession {

    /** Camera whose stream/engine is currently open, or `null` when closed. */
    val activeCameraId: StateFlow<CameraId?>

    /**
     * Makes [cameraId] the active camera.
     *
     * Opening the already-active camera is idempotent. When another camera is
     * active, the old session is closed first; if closing fails, the new camera
     * is not opened and [CameraSessionResult.Failed] is returned.
     */
    suspend fun open(cameraId: CameraId): CameraSessionResult

    /** Closes the active session, if any. Safe to call when already closed. */
    suspend fun close()
}

/** What happened when the user opened a camera. */
sealed interface CameraSessionResult {
    data object Opened : CameraSessionResult
    data class Failed(val reason: String) : CameraSessionResult
}

/** Session used by previews and tests where no engine exists. */
class InMemoryCameraSession : CameraSession {

    private val _activeCameraId = MutableStateFlow<CameraId?>(null)
    override val activeCameraId: StateFlow<CameraId?> = _activeCameraId

    /** Test hook: count how many times the previous session was closed. */
    var closeCount: Int = 0
        private set

    override suspend fun open(cameraId: CameraId): CameraSessionResult {
        if (_activeCameraId.value == cameraId) return CameraSessionResult.Opened
        if (_activeCameraId.value != null) closeCount++
        _activeCameraId.value = cameraId
        return CameraSessionResult.Opened
    }

    override suspend fun close() {
        _activeCameraId.value = null
    }
}