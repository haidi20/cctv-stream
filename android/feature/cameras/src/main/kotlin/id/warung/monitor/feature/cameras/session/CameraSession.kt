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
     * Whether the open camera is actually counting tables right now, and why
     * not when it is not.
     *
     * A session can be open (preview running, map editable) while inference is
     * impossible. The UI must be able to say which prerequisite is missing
     * instead of showing an em dash with no explanation
     * (`docs/kotlin-rust-gaps.md` G-02).
     */
    val counting: StateFlow<CountingState>

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

    /**
     * The session accepted the camera. [counting] is carried in the result as
     * well as published on [CameraSession.counting] so a caller that ignores
     * the flow still learns why the totals are empty.
     */
    data class Opened(
        val counting: CountingState = CountingState.Unknown,
    ) : CameraSessionResult

    data class Failed(val reason: String) : CameraSessionResult
}

/**
 * Whether Rust can count tables for the open camera.
 *
 * `Unknown` means "no session, or nothing has been decided yet"; it is never
 * rendered as an explanation. `Inactive` always carries a specific cause so
 * the UI can name it.
 */
sealed interface CountingState {
    data object Unknown : CountingState
    data object Active : CountingState
    data class Inactive(val reason: CountingReason) : CountingState
}

/**
 * Why inference is not running.
 *
 * Kept as a closed enum, not a string, so every cause gets its own user-facing
 * sentence and a test can prove no two causes collapse into the same text.
 */
enum class CountingReason {
    /** `assets/models/yolov11n.onnx` (or the copied file) is absent or empty. */
    ModelMissing,

    /** The ONNX Runtime shared library did not load or was not found. */
    RuntimeMissing,

    /** Total RAM is below the inference floor, so the rate is zero. */
    RamTooLow,

    /** No polygon has been saved for this camera yet. */
    NoTables,

    /** Demo camera: there is no real stream to analyse. */
    Demo,

    /** The saved map could not be encoded into the engine's JSON. */
    ConfigInvalid,

    /** `Engine.start` threw; the engine stays stopped and usable. */
    StartFailed,
}

/** Session used by previews and tests where no engine exists. */
class InMemoryCameraSession : CameraSession {

    private val _activeCameraId = MutableStateFlow<CameraId?>(null)
    override val activeCameraId: StateFlow<CameraId?> = _activeCameraId

    private val _counting = MutableStateFlow<CountingState>(CountingState.Unknown)
    override val counting: StateFlow<CountingState> = _counting

    /** Test hook: count how many times the previous session was closed. */
    var closeCount: Int = 0
        private set

    override suspend fun open(cameraId: CameraId): CameraSessionResult {
        if (_activeCameraId.value == cameraId) {
            return CameraSessionResult.Opened(_counting.value)
        }
        if (_activeCameraId.value != null) closeCount++
        _activeCameraId.value = cameraId
        return CameraSessionResult.Opened(_counting.value)
    }

    override suspend fun close() {
        _activeCameraId.value = null
        _counting.value = CountingState.Unknown
    }

    /**
     * Publishes a decision without an engine.
     *
     * Used by previews and tests: a fake session has no planner, so it must be
     * able to say "counting" or name a cause explicitly.
     */
    fun publishCounting(counting: CountingState) {
        _counting.value = counting
    }
}
