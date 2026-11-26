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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val network: NetworkMonitor = NetworkMonitor { kotlinx.coroutines.flow.flowOf(NetworkReach.WifiPrivate) },
    private val failureReporter: StreamFailureReporter = StreamFailureReporter { },
    private val monitoring: MonitoringSignal = MonitoringSignal { },
    private val engineChanges: EngineChangeSignal = EngineChangeSignal { emptyFlow() },
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Serialises poll and event refreshes so a slow read cannot land late. */
    private val refreshMutex = Mutex()

    private var selectedCameraId: CameraId? = null
    private var openedCameraId: CameraId? = null
    private var pollStarted = false
    private var reach: NetworkReach = NetworkReach.WifiPrivate
    private var lastFailure: StreamErrorKind? = null

    // Backoff state: attempt 1..MAX, and a generation token so a retry that
    // was scheduled for an old failure can never land on a new session.
    private var reconnectAttempt = 0
    private var reconnectGeneration = 0

    init {
        observeCameras()
        observePreview()
        observeNetwork()
        observeEngineChanges()
        startPolling()
        publishMonitoringState()
    }

    /**
     * Mirrors the rendered state to the foreground service, including the
     * cleared summary after a failure. The service dedups; the engine remains
     * the only owner of counts.
     */
    private fun publishMonitoringState() {
        viewModelScope.launch {
            state.collect { current ->
                monitoring.publish(
                    MonitoringSample(stream = current.stream, summary = current.summary),
                )
            }
        }
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
                when (mapped) {
                    is StreamUiState.Live -> {
                        // A live stream clears the ladder, so the next failure
                        // starts again at attempt 1.
                        reconnectAttempt = 0
                        _state.update { it.copy(stream = mapped) }
                    }
                    is StreamUiState.Failed -> {
                        lastFailure = mapped.kind
                        // Rust owns the tables and cannot see that the video
                        // died; without this report it would keep the old
                        // counts until its own watchdog fires.
                        reportFailure(mapped.kind)
                        _state.update {
                            it.copy(
                                stream = mapped,
                                summary = null,
                                summaryStale = it.summary != null,
                                lastUpdatedMillis = null,
                            )
                        }
                        scheduleReconnect(mapped.kind)
                    }
                    else -> _state.update { it.copy(stream = mapped) }
                }
            }
        }
    }

    /**
     * Tells the engine that the video is gone so it marks every table
     * `unknown` now instead of after its own watchdog. Reporting is best
     * effort: a stopped engine legitimately answers `NotRunning`, and the UI
     * already shows the failure.
     */
    private fun reportFailure(kind: StreamErrorKind) {
        if (!ReconnectPolicy.isReportable(kind)) return
        viewModelScope.launch {
            try {
                failureReporter.report(kind)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // The engine may be stopped; the failure label is already shown.
            }
        }
    }

    /**
     * Schedules one backoff step for a transport failure.
     *
     * `AuthFailed`/`Protocol` never enter the ladder, and a network problem is
     * left to [NetworkMonitor] so the app does not hammer a camera that is
     * merely unreachable because the phone left the warung Wi-Fi. Each wait is
     * a `delay` inside [viewModelScope]; the generation token makes a queued
     * step a no-op once the user picks another camera or a new open begins.
     */
    private fun scheduleReconnect(kind: StreamErrorKind) {
        if (!ReconnectPolicy.isRetryable(kind)) return
        if (reconnectAttempt >= ReconnectPolicy.MAX_ATTEMPTS) return
        val camera = _state.value.camera ?: return
        reconnectAttempt += 1
        val attempt = reconnectAttempt
        val generation = ++reconnectGeneration
        val waitMillis = ReconnectPolicy.delayMillis(attempt)
        _state.update { it.copy(stream = StreamUiState.Reconnecting(attempt, waitMillis)) }
        viewModelScope.launch {
            delay(waitMillis)
            if (generation != reconnectGeneration) return@launch
            if (reach != NetworkReach.WifiPrivate) return@launch
            if (_state.value.camera?.id != camera.id) return@launch
            openCamera(camera, reconnectOf = attempt)
        }
    }

    /**
     * Tracks the transport so `no_network`/`wrong_network` can be shown without
     * touching the camera. When Wi-Fi returns and the last problem was purely a
     * network one, the stream retries exactly once; an auth failure never does.
     */
    private fun observeNetwork() {
        viewModelScope.launch {
            network.reach().collect { next ->
                val previous = reach
                reach = next
                val camera = _state.value.camera ?: return@collect
                val blocked = NetworkPolicy.blockReason(next, camera.source is id.warung.monitor.core.model.CameraSource.Rtsp)
                if (blocked != null) {
                    // Any queued backoff is meaningless without a local link,
                    // so cancel it before it can fire on a dead socket.
                    reconnectGeneration += 1
                    // A transport problem must not overwrite an auth failure:
                    // otherwise Wi-Fi returning could retry a 401 and lock the
                    // camera account. The password is wrong regardless of link.
                    if (NetworkPolicy.shouldShowTransportProblem(lastFailure)) {
                        lastFailure = blocked
                        _state.update { it.copy(stream = StreamUiState.Failed(blocked), summary = null) }
                    }
                    return@collect
                }
                if (next == NetworkReach.WifiPrivate && previous != NetworkReach.WifiPrivate) {
                    if (NetworkPolicy.shouldRetryAfterNetworkReturns(lastFailure)) {
                        lastFailure = null
                        retry()
                    }
                }
            }
        }
    }

    /**
     * Refreshes as soon as Rust reports a stream or table transition instead of
     * waiting for the next poll tick. The signal carries no snapshot, so this
     * only shortens latency; [startPolling] still runs as a heartbeat in case a
     * listener could not be registered or an event was dropped.
     */
    private fun observeEngineChanges() {
        viewModelScope.launch {
            // The signal is an event, never a snapshot. The source emits one
            // baseline tick after its listener is registered, so this reads
            // once immediately and then on every transition Rust reports.
            engineChanges.changes().collect {
                refreshStatus()
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
    suspend fun refreshStatus() = refreshMutex.withLock {
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
        // Rust can still look `Connected` for up to its 5 s watchdog, so the
        // preview failure is the tie-breaker: no live video, no current counts.
        val videoKnownDead = _state.value.stream.let {
            it is StreamUiState.Failed || it is StreamUiState.Reconnecting
        }
        val usable = status.available &&
            matchesSelection &&
            status.phase != EnginePhase.Failed &&
            !videoKnownDead
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
                    // The engine can still look alive for up to its 5 s
                    // watchdog after the video died. A poll must not upgrade
                    // a real failure (or a pending retry) back to "Live".
                    EnginePhase.Live -> current.stream as? StreamUiState.Live
                        ?: if (current.stream is StreamUiState.Failed ||
                            current.stream is StreamUiState.Reconnecting
                        ) {
                            current.stream
                        } else {
                            StreamUiState.Live(clock.nowMillis())
                        }
                    // Rust knows why the stream died; keep `auth_failed`
                    // distinct from a network timeout so the user is not told
                    // to fix Wi-Fi when the camera password is wrong.
                    EnginePhase.Failed -> StreamUiState.Failed(
                        status.failure ?: StreamErrorKind.Unknown,
                    )
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
                // Only a live engine can judge calibration; a stopped or
                // unavailable engine must not raise a banner it cannot verify.
                needsRecalibration = status.available &&
                    matchesSelection &&
                    status.phase != EnginePhase.Failed &&
                    status.needsRecalibration,
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
        // A manual retry is a fresh start, not a continuation of the ladder.
        reconnectAttempt = 0
        reconnectGeneration += 1
        _state.update { it.copy(stream = StreamUiState.Connecting, banner = null) }
        openCamera(camera)
    }

    fun dismissBanner() {
        _state.update { it.copy(banner = null) }
    }

    /**
     * Opens one camera. [reconnectOf] is the backoff attempt that triggered
     * this call, or 0 for a user-initiated open; only a user open resets the
     * ladder, so a retry does not restart its own counter.
     */
    private fun openCamera(camera: CameraProfile, reconnectOf: Int = 0) {
        if (reconnectOf == 0) {
            reconnectAttempt = 0
            reconnectGeneration += 1
        }
        viewModelScope.launch {
            // Never open a socket on cellular or with no link: that is the
            // `wrong_network`/`no_network` decision, not a camera defect.
            val sourceIsRtsp = camera.source is id.warung.monitor.core.model.CameraSource.Rtsp
            val blocked = NetworkPolicy.blockReason(reach, sourceIsRtsp)
            if (blocked != null) {
                lastFailure = blocked
                _state.update { it.copy(stream = StreamUiState.Failed(blocked), banner = blocked.message) }
                return@launch
            }
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
