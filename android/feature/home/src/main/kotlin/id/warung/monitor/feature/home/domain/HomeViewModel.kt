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
import id.warung.monitor.feature.cameras.session.CountingState
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val analysisSource: EngineAnalysisSource? = null,
    private val monitoringPreference: MonitoringPreference = InMemoryMonitoringPreference(),
    private val secrets: CameraSecretReader = CameraSecretReader { null },
    private val mappingSource: TableMappingSource = TableMappingSource { emptyFlow() },
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

    /** Pending auto-hide for the transient justReconnected flag. */
    private var reconnectedJob: Job? = null

    // Backoff state: attempt 1..MAX, and a generation token so a retry that
    // was scheduled for an old failure can never land on a new session.
    private var reconnectAttempt = 0
    private var reconnectGeneration = 0
    /** Set for the whole click burst; preview events cannot clear it. */
    private var reconnectClickInFlight = false

    init {
        observeMonitoringPreference()
        observeCameras()
        observePreview()
        observeNetwork()
        observeEngineChanges()
        observeMapping()
        observeCounting()
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

    /**
     * Mirrors the session's counting decision into the UI.
     *
     * The session publishes it again whenever the reason changes, including
     * after a map is saved mid-session (`applyTables` may start the engine), so
     * Home never keeps a stale "model belum terpasang" line
     * (`docs/kotlin-rust-gaps.md` G-02).
     */
    private fun observeCounting() {
        viewModelScope.launch {
            session.counting.collect { counting ->
                _state.update { it.copy(counting = counting) }
            }
        }
    }

    private fun observeCameras() {
        viewModelScope.launch {
            repository.observeAll().collect { cameras ->
                val monitoringOn = _state.value.monitoringEnabled
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
                if (!monitoringOn) closeEverythingForMonitoringOff()
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
                val regained = next == NetworkReach.WifiPrivate && previous != NetworkReach.WifiPrivate
                // Publish the transport itself before any early return: the
                // banner must appear even when no camera is selected yet.
                _state.update { it.copy(network = next, justReconnected = regained) }
                if (regained) scheduleReconnectedDismiss()
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
                if (regained) {
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

    /**
     * Tracks whether the selected camera already has polygons in Room.
     *
     * This is the gate for the analysis section: Rust cannot judge a person box
     * without a polygon, so a camera with no map shows a mapping prompt instead
     * of an empty panel. The read follows the selection, so switching cameras
     * never carries the previous camera's map over.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeMapping() {
        viewModelScope.launch {
            _state
                .map { it.camera?.id }
                .distinctUntilChanged()
                .flatMapLatest { cameraId ->
                    if (cameraId == null) {
                        flowOf(TableMapState.Unknown)
                    } else {
                        mappingSource.observe(cameraId)
                            .map { saved -> saved.toMapState() }
                            // A failed read must not claim "belum ada peta": the
                            // user would redraw a map that already exists.
                            .catch { emit(TableMapState.Unknown) }
                    }
                }
                .collect { mapping -> _state.update { it.copy(mapping = mapping) } }
        }
    }

    /**
     * A saved revision with zero polygons cannot analyse anything, so it counts
     * as missing and the UI asks for a map instead of showing an empty panel.
     */
    private fun SavedTableMap?.toMapState(): TableMapState = when {
        this == null || tables <= 0 -> TableMapState.Missing
        else -> TableMapState.Mapped(tables = tables, revision = revision)
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
        startDetectionPolling()
    }

    /**
     * Refreshes only the person boxes, on its own faster beat.
     *
     * Tables and totals keep the slow poll: they change when a person sits or
     * leaves, not when they shift in their chair. Boxes are the opposite - a
     * one-second wait let the overlay trail a walking person by up to a table
     * width (`docs/detection-quality-todo.md` Q-02). The loop only reads while
     * the stream is live and a session is open; a dead camera costs nothing.
     */
    private fun startDetectionPolling() {
        val fast = pollInterval.detectionMillisOrStatus()
        // A disabled or single-cadence test interval must not add a second loop.
        if (fast <= 0L || fast >= pollInterval.millis) return
        viewModelScope.launch {
            while (isActive) {
                delay(fast)
                refreshDetections()
            }
        }
    }

    /**
     * Reads the boxes without touching tables or totals.
     *
     * Deliberately silent on failure: the status poll owns the error banner,
     * and a box read that throws must leave the last good frame on screen
     * rather than blank the overlay.
     */
    internal suspend fun refreshDetections() {
        val source = analysisSource ?: return
        val current = _state.value
        val camera = current.camera ?: return
        if (current.stream !is StreamUiState.Live) return
        if (camera.source is CameraSource.Demo) return
        val opened = openedCameraId ?: return
        if (opened != camera.id) return
        val boxes = try {
            source.readDetections(camera)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        } ?: return
        _state.update { it.copy(detections = boxes) }
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
        // Detail is only requested for the camera this ViewModel actually
        // opened, and only while Rust still reports it live. A different
        // camera id would describe someone else's tables; a stopped engine
        // has nothing to show, so the extra FFI read is skipped entirely.
        // The preview failure is the tie-breaker: Rust can look `Live` for up
        // to its watchdog after the video died, but the rows are already stale.
        val opened = openedCameraId
        val videoDied = _state.value.stream.let {
            it is StreamUiState.Failed || it is StreamUiState.Reconnecting
        }
        val analysis = readAnalysis(
            camera = selected,
            sessionMatches = matchesSelection &&
                opened != null &&
                opened == selected.id &&
                status.phase != EnginePhase.Failed &&
                !videoDied,
        )
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
                // `null` from the source means "no current session"; keeping
                // the previous rows would resurrect counts from a dead stream.
                tables = analysis?.tables,
                detections = analysis?.detections.orEmpty(),
                inferenceFps = analysis?.inferenceFps ?: 0.0,
                lastAnalysisAgeMillis = analysis?.lastAnalysisAgeMillis,
                frameToInferenceMillis = analysis?.frameToInferenceMillis,
                // The clock moves only when Rust's rows change, so a steady
                // table does not flicker every poll. The age comes from Rust;
                // Kotlin never re-derives when inference actually happened.
                lastAnalysisMillis = when {
                    analysis == null -> null
                    current.tables != analysis.tables ->
                        analysis.lastAnalysisAgeMillis?.let { clock.nowMillis() - it }
                            ?: current.lastAnalysisMillis
                    else -> current.lastAnalysisMillis
                },
                analysisFrameAtMillis = when {
                    analysis == null -> null
                    current.tables != analysis.tables ->
                        analysis.lastAnalysisAgeMillis?.let { clock.nowMillis() - it }
                            ?: current.analysisFrameAtMillis
                    else -> current.analysisFrameAtMillis
                },
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
                viewChangedFraction = status.viewChangedFraction.takeIf {
                    status.available && matchesSelection && status.needsRecalibration
                },
            )
        }
    }

    /**
     * Reads the Rust-owned table rows and boxes, never inventing counts.
     *
     * Only called when the selected camera owns the session; anything else
     * leaves the panel hidden instead of showing another camera's tables.
     */
    private suspend fun readAnalysis(
        camera: CameraProfile?,
        sessionMatches: Boolean,
    ): EngineAnalysis? {
        val source = analysisSource ?: return null
        val target = camera ?: return null
        if (!sessionMatches) return null
        if (target.source is CameraSource.Demo) return null
        return try {
            source.readAnalysis(target)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    fun selectCamera(cameraId: CameraId) {
        val state = _state.value
        // A paused camera keeps its card in the list so the user can resume it,
        // but it must never open a stream until then.
        if (!state.monitoringEnabled) return
        val camera = state.cameras.firstOrNull { it.id == cameraId } ?: return
        if (!camera.monitoringEnabled) return
        // Tapping the camera that is already streaming is a no-op; the first tap
        // on the default selection must still open it.
        if (openedCameraId == cameraId) return
        selectedCameraId = cameraId
        _state.update { it.copy(camera = camera, summary = null, lastUpdatedMillis = null) }
        openCamera(camera)
    }

    /**
     * Master switch. Off closes the open stream now and hides the saved camera
     * lists; on restores them without re-scanning or re-mapping.
     */
    fun setMonitoringEnabled(enabled: Boolean) {
        if (_state.value.monitoringEnabled == enabled) return
        _state.update { it.copy(monitoringEnabled = enabled) }
        viewModelScope.launch { monitoringPreference.set(enabled) }
        if (!enabled) {
            closeEverythingForMonitoringOff()
        } else {
            _state.update {
                it.copy(
                    camera = selectedCameraId?.let { id ->
                        it.cameras.firstOrNull { camera -> camera.id == id }
                    } ?: it.cameras.firstOrNull { camera -> camera.monitoringEnabled },
                )
            }
        }
    }

    /**
     * Per-camera switch. Pausing the open camera stops its stream at once;
     * other cameras are untouched.
     */
    fun setCameraMonitoring(cameraId: CameraId, enabled: Boolean) {
        viewModelScope.launch { repository.setMonitoringEnabled(cameraId, enabled) }
        if (enabled) return
        if (openedCameraId == cameraId || _state.value.camera?.id == cameraId) {
            closeCamera()
            _state.update {
                it.copy(
                    stream = StreamUiState.Idle,
                    summary = null,
                    summaryStale = false,
                    lastUpdatedMillis = null,
                    detections = emptyList(),
                    tables = null,
                )
            }
        }
    }

    private fun closeEverythingForMonitoringOff() {
        reconnectGeneration += 1
        closeCamera()
        _state.update {
            it.copy(
                stream = StreamUiState.Idle,
                summary = null,
                summaryStale = false,
                lastUpdatedMillis = null,
                detections = emptyList(),
                tables = null,
                overlay = HomeOverlay.None,
                camera = null,
                counting = CountingState.Unknown,
            )
        }
    }

    private fun closeCamera() {
        openedCameraId = null
        viewModelScope.launch {
            try {
                preview.close()
                session.close()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Closing is best effort; the UI is already idle.
            }
        }
    }

    /**
     * Mirrors the persisted master switch. The first emission also decides
     * whether the initial camera list is allowed to open a stream.
     */
    private fun observeMonitoringPreference() {
        viewModelScope.launch {
            monitoringPreference.observe().collect { enabled ->
                val changed = _state.value.monitoringEnabled != enabled
                _state.update { it.copy(monitoringEnabled = enabled) }
                if (!enabled && changed) closeEverythingForMonitoringOff()
            }
        }
    }

    /** JVM default when no DataStore is wired; the master switch stays on. */
    private class InMemoryMonitoringPreference : MonitoringPreference {
        private val enabled = kotlinx.coroutines.flow.MutableStateFlow(true)
        override fun observe(): kotlinx.coroutines.flow.Flow<Boolean> = enabled
        override suspend fun set(enabled: Boolean) {
            this.enabled.value = enabled
        }
    }

    /**
     * Starts or stops analysis without touching the saved camera or map.
     * Stopping pauses the frame pump but keeps the preview alive, so starting
     * resumes the newest frames of the same stream without a reconnect.
     */
    fun setAnalysisEnabled(enabled: Boolean) {
        if (enabled && _state.value.needsMapping) return
        if (_state.value.analysisEnabled == enabled) return
        _state.update { it.copy(analysisEnabled = enabled) }
        if (enabled) {
            // A resume reads the newest frames already arriving from the
            // running stream; the next poll republishes Rust's current rows.
            viewModelScope.launch { refreshStatus() }
            return
        }
        _state.update {
            it.copy(
                summary = null,
                summaryStale = false,
                lastUpdatedMillis = null,
                lastAnalysisMillis = null,
                analysisFrameAtMillis = null,
                frameToInferenceMillis = null,
                lastAnalysisAgeMillis = null,
                inferenceFps = 0.0,
                detections = emptyList(),
                tables = null,
                needsRecalibration = false,
            )
        }
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

    /**
     * Explicit reconnect from the user: drop the current session and open the
     * selected camera again from scratch. Unlike [retry], this also releases
     * the old preview and engine session before a new one starts.
     */
    fun reconnect() {
        val state = _state.value
        val camera = state.camera ?: return
        if (!state.monitoringEnabled || !camera.monitoringEnabled) return
        if (state.reconnecting || reconnectClickInFlight) return
        reconnectClickInFlight = true
        _state.update { it.copy(reconnecting = true) }
        closeCamera()
        reconnectAttempt = 0
        reconnectGeneration += 1
        lastFailure = null
        _state.update {
            it.copy(
                stream = StreamUiState.Connecting,
                banner = null,
                summary = null,
                detections = emptyList(),
                tables = null,
                reconnecting = true,
            )
        }
        openCamera(camera)
        reconnectClickInFlight = false
    }

    fun dismissBanner() {
        _state.update { it.copy(banner = null) }
    }

    /**
     * Clears the transient "back online" note after its display window.
     *
     * The window is owned here, not by the UI, so a recomposition can never
     * restart the timer. A second regain replaces the pending window instead
     * of stacking a second one.
     */
    private fun scheduleReconnectedDismiss() {
        reconnectedJob?.cancel()
        reconnectedJob = viewModelScope.launch {
            delay(RECONNECTED_VISIBLE_MS)
            _state.update { it.copy(justReconnected = false) }
        }
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
                is CameraSessionResult.Opened -> {
                    openedCameraId = camera.id
                    _state.update { it.copy(counting = result.counting) }
                    val secret = try {
                        secrets.read(camera.id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                    try {
                        preview.open(camera, secret = secret)
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
                            // A session that never opened cannot explain the
                            // dash; the banner already names the reason.
                            counting = CountingState.Unknown,
                        )
                    }
                }
            }
        }
    }

    companion object {
        val POLL_INTERVAL_MS: Long = HomePollInterval.DEFAULT_MILLIS

        /** How long the "Anda kembali online" note stays on screen. */
        const val RECONNECTED_VISIBLE_MS: Long = 2_500L
    }
}
