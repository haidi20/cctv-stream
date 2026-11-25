package id.warung.monitor.feature.home.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.feature.cameras.data.CameraRepository
import id.warung.monitor.feature.cameras.session.CameraSession
import id.warung.monitor.feature.cameras.session.CameraSessionResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Maps engine health to the two numbers the user sees.
 *
 * The ViewModel never counts tables itself: counts come from Rust through
 * [EngineStatusSource]. A camera with no configuration, a missing model, or a
 * dead stream keeps `summary == null`, so the UI shows an em dash instead of a
 * misleading zero.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: CameraRepository,
    private val session: CameraSession,
    private val statusSource: EngineStatusSource,
    private val preview: CameraPreviewCoordinator,
    private val clock: HomeClock,
    private val pollInterval: HomePollInterval,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var selectedCameraId: CameraId? = null
    private var openedCameraId: CameraId? = null
    private var pollStarted = false

    init {
        observeCameras()
        observePreview()
        startPolling()
    }

    private fun observeCameras() {
        viewModelScope.launch {
            repository.observeAll().collect { cameras ->
                val selected = selectedCameraId
                    ?.let { id -> cameras.firstOrNull { it.id == id } }
                    ?: cameras.firstOrNull()
                if (selectedCameraId != selected?.id) {
                    selectedCameraId = selected?.id
                }
                _state.update {
                    it.copy(
                        cameras = cameras,
                        camera = selected,
                        loading = false,
                        demoMode = selected?.source is CameraSource.Demo,
                    )
                }
                // Opening a camera is an explicit user action (Scan CCTV), never
                // a side effect of the list loading. This is also what lets the
                // user "open one camera at a time" without a surprise stream.
            }
        }
    }

    private fun observePreview() {
        viewModelScope.launch {
            preview.state.collect { previewState ->
                val mapped = when (previewState) {
                    PreviewState.Idle -> StreamUiState.Idle
                    PreviewState.Connecting -> StreamUiState.Connecting
                    is PreviewState.Playing -> StreamUiState.Live(clock.nowMillis())
                    is PreviewState.Failed -> StreamUiState.Failed(previewState.kind)
                }
                _state.update {
                    when (mapped) {
                        is StreamUiState.Live -> it.copy(stream = mapped)
                        is StreamUiState.Failed -> it.copy(stream = mapped, summaryStale = it.summary != null)
                        else -> it.copy(stream = mapped)
                    }
                }
            }
        }
    }

    private fun startPolling() {
        val interval = pollInterval.millis
        if (pollStarted || interval <= 0L) return
        pollStarted = true
        viewModelScope.launch {
            while (isActive) {
                delay(interval)
                refreshStatus()
            }
        }
    }

    /** One read of the engine; also used by tests after a fake status change. */
    suspend fun refreshStatus() {
        val status = try {
            statusSource.read(_state.value.camera)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            EngineStatus.Unavailable
        }
        val selected = _state.value.camera
        val matchesSelection = selected != null && status.cameraId == selected.id.value
        // A dead stream must never keep showing the last good totals: an empty
        // table and an unseen table are different answers (AGENTS.md rule 5).
        val usable = status.available && matchesSelection && status.phase != EnginePhase.Failed
        val summary = if (usable) status.summary else null
        _state.update { current ->
            val sameSummary = current.summary == summary
            val stream = if (status.available && matchesSelection) {
                when (status.phase) {
                    EnginePhase.Idle -> StreamUiState.Idle
                    // The engine waits for its first frame; Media3 already knows
                    // the video is playing. Only a frame may override the video.
                    EnginePhase.Connecting -> if (status.framesIn > 0L) {
                        StreamUiState.Connecting
                    } else {
                        current.stream.takeIf { it is StreamUiState.Live }
                            ?: StreamUiState.Connecting
                    }
                    EnginePhase.Live -> current.stream as? StreamUiState.Live
                        ?: StreamUiState.Live(clock.nowMillis())
                    EnginePhase.Failed -> StreamUiState.Failed(StreamErrorKind.Unknown)
                }
            } else {
                current.stream
            }
            current.copy(
                stream = stream,
                summary = summary,
                summaryStale = summary == null && current.summary != null,
                // Only a changed snapshot moves the "diperbarui" clock.
                lastUpdatedMillis = when {
                    summary == null -> null
                    sameSummary -> current.lastUpdatedMillis
                    else -> clock.nowMillis()
                },
                modelReady = status.modelReady,
                demoMode = selected?.source is CameraSource.Demo,
            )
        }
    }

    fun selectCamera(cameraId: CameraId) {
        val camera = _state.value.cameras.firstOrNull { it.id == cameraId } ?: return
        // Tapping the camera that is already streaming is a no-op; the first tap
        // on the default selection must still open it.
        if (openedCameraId == cameraId) return
        selectedCameraId = cameraId
        _state.update { it.copy(camera = camera, summary = null, lastUpdatedMillis = null) }
        openCamera(camera)
    }

    fun openScan() {
        _state.update { it.copy(overlay = HomeOverlay.Scan) }
    }

    fun dismissScan() {
        _state.update { it.copy(overlay = HomeOverlay.None) }
    }

    fun retry() {
        val camera = _state.value.camera ?: return
        _state.update { it.copy(stream = StreamUiState.Connecting) }
        openCamera(camera)
    }

    fun dismissBanner() {
        _state.update { it.copy(banner = null) }
    }

    private fun openCamera(camera: CameraProfile) {
        viewModelScope.launch {
            _state.update { it.copy(stream = StreamUiState.Connecting, banner = null) }
            val result = try {
                session.open(camera.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                CameraSessionResult.Failed("Sesi kamera gagal dibuka")
            }
            when (result) {
                CameraSessionResult.Opened -> {
                    openedCameraId = camera.id
                    try {
                        preview.open(camera, secret = null)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        openedCameraId = null
                        _state.update {
                            it.copy(stream = StreamUiState.Failed(StreamErrorKind.Unknown))
                        }
                    }
                }
                is CameraSessionResult.Failed -> {
                    openedCameraId = null
                    _state.update {
                        it.copy(
                            stream = StreamUiState.Failed(StreamErrorKind.Unknown),
                            banner = result.reason,
                        )
                    }
                }
            }
        }
    }

    companion object {
        val POLL_INTERVAL_MS: Long = HomePollInterval.DEFAULT_MILLIS
    }
}