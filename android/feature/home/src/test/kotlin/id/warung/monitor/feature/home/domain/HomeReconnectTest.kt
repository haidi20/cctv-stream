package id.warung.monitor.feature.home.domain

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.feature.cameras.data.InMemoryCameraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Backoff is observable state, not just a hidden timer. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeReconnectTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        repo: InMemoryCameraRepository,
        preview: RecordingPreview,
        reporter: StreamFailureReporter = RecordingFailureReporter(),
        statusSource: EngineStatusSource = MutableStatusSource(),
        monitoring: MonitoringSignal = MonitoringSignal { },
    ) = HomeViewModel(
        repository = repo,
        session = id.warung.monitor.feature.cameras.session.InMemoryCameraSession(),
        statusSource = statusSource,
        preview = preview,
        clock = { 1_000L },
        pollInterval = HomePollInterval(0L),
        network = MutableNetworkMonitor(),
        failureReporter = reporter,
        monitoring = monitoring,
    )

    private fun rtspRepo() = InMemoryCameraRepository(
        listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream"))),
    )

    @Test
    fun a_timeout_shows_reconnecting_then_retries_after_the_backoff() = runTest(dispatcher) {
        val preview = RecordingPreview()
        val model = viewModel(rtspRepo(), preview)
        testScheduler.runCurrent()

        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()
        preview.fail(StreamErrorKind.Timeout)
        testScheduler.runCurrent()

        assertEquals(StreamUiState.Reconnecting(1, ReconnectPolicy.delayMillis(1)), model.state.value.stream)
        assertEquals(1, preview.opened.size)

        testScheduler.advanceTimeBy(ReconnectPolicy.delayMillis(1))
        testScheduler.runCurrent()

        assertEquals("the same camera is reopened once", listOf("cam-1", "cam-1"), preview.opened)
    }

    @Test
    fun wifi_dropping_during_the_backoff_shows_the_network_problem_instead_of_lying() = runTest(dispatcher) {
        val preview = RecordingPreview().apply { autoPlayOnOpen = false }
        val network = MutableNetworkMonitor()
        val model = HomeViewModel(
            repository = rtspRepo(),
            session = id.warung.monitor.feature.cameras.session.InMemoryCameraSession(),
            statusSource = MutableStatusSource(),
            preview = preview,
            clock = { 1_000L },
            pollInterval = HomePollInterval(0L),
            network = network,
        )
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        preview.fail(StreamErrorKind.Timeout)
        testScheduler.runCurrent()
        assertEquals(StreamUiState.Reconnecting(1, ReconnectPolicy.delayMillis(1)), model.state.value.stream)

        network.emit(NetworkReach.Offline)
        testScheduler.runCurrent()

        assertEquals(
            "a pending retry must not keep claiming reconnection while offline",
            StreamUiState.Failed(StreamErrorKind.NoNetwork),
            model.state.value.stream,
        )
        val opensWhileOffline = preview.opened.size
        testScheduler.advanceTimeBy(60_000L)
        testScheduler.runCurrent()
        assertEquals("no socket while offline", opensWhileOffline, preview.opened.size)
    }

    @Test
    fun a_live_stream_resets_the_attempt_counter() = runTest(dispatcher) {
        val preview = RecordingPreview()
        val model = viewModel(rtspRepo(), preview)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        preview.fail(StreamErrorKind.Timeout)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(ReconnectPolicy.delayMillis(1))
        testScheduler.runCurrent()
        assertTrue(model.state.value.stream is StreamUiState.Live)

        preview.fail(StreamErrorKind.Timeout)
        testScheduler.runCurrent()

        assertEquals("a fresh failure starts again at attempt 1", StreamUiState.Reconnecting(1, ReconnectPolicy.delayMillis(1)), model.state.value.stream)
    }

    @Test
    fun an_auth_failure_never_enters_the_backoff_loop() = runTest(dispatcher) {
        val preview = RecordingPreview()
        val model = viewModel(rtspRepo(), preview)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        preview.fail(StreamErrorKind.AuthFailed)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(60_000L)
        testScheduler.runCurrent()

        assertEquals(StreamUiState.Failed(StreamErrorKind.AuthFailed), model.state.value.stream)
        assertEquals("a 401 must be opened exactly once", 1, preview.opened.size)
    }

    @Test
    fun a_transport_failure_is_reported_to_the_engine_and_clears_the_totals() = runTest(dispatcher) {
        val reporter = RecordingFailureReporter()
        val statusSource = MutableStatusSource(status(camera = "cam-1"))
        val preview = RecordingPreview().apply { autoPlayOnOpen = false }
        val model = viewModel(rtspRepo(), preview, reporter, statusSource)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()
        model.refreshStatus()
        testScheduler.runCurrent()
        assertTrue("precondition: totals were shown", model.state.value.summary != null)

        preview.fail(StreamErrorKind.Timeout)
        testScheduler.runCurrent()

        assertEquals(listOf(StreamErrorKind.Timeout), reporter.reported)
        assertEquals("a dead stream must not keep stale counts", null, model.state.value.summary)
        assertTrue(model.state.value.summaryStale)
        assertEquals(null, model.state.value.lastUpdatedMillis)
    }

    @Test
    fun a_network_problem_is_not_reported_because_no_stream_was_opened() = runTest(dispatcher) {
        val reporter = RecordingFailureReporter()
        val repo = InMemoryCameraRepository(
            listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream"))),
        )
        val preview = RecordingPreview()
        val model = HomeViewModel(
            repository = repo,
            session = id.warung.monitor.feature.cameras.session.InMemoryCameraSession(),
            statusSource = MutableStatusSource(),
            preview = preview,
            clock = { 1_000L },
            pollInterval = HomePollInterval(0L),
            network = MutableNetworkMonitor(NetworkReach.Offline),
            failureReporter = reporter,
        )
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        assertEquals(StreamUiState.Failed(StreamErrorKind.NoNetwork), model.state.value.stream)
        assertEquals(emptyList<StreamErrorKind>(), reporter.reported)
    }

    @Test
    fun a_poll_does_not_upgrade_a_dead_stream_back_to_live() = runTest(dispatcher) {
        val statusSource = MutableStatusSource(status(camera = "cam-1", phase = EnginePhase.Live))
        val preview = RecordingPreview().apply { autoPlayOnOpen = false }
        val model = viewModel(rtspRepo(), preview, statusSource = statusSource)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        preview.fail(StreamErrorKind.Closed)
        testScheduler.runCurrent()
        assertEquals(StreamUiState.Reconnecting(1, ReconnectPolicy.delayMillis(1)), model.state.value.stream)

        model.refreshStatus()
        testScheduler.runCurrent()

        assertEquals(
            "the engine watchdog has not fired yet; the UI must not claim live video",
            StreamUiState.Reconnecting(1, ReconnectPolicy.delayMillis(1)),
            model.state.value.stream,
        )
        assertEquals(
            "stale Rust health must not repopulate the cards",
            null,
            model.state.value.summary,
        )
    }

    @Test
    fun the_notification_layer_receives_the_same_state_the_screen_shows() = runTest(dispatcher) {
        val monitoring = RecordingMonitoringSignal()
        val statusSource = MutableStatusSource(status(camera = "cam-1"))
        val preview = RecordingPreview().apply { autoPlayOnOpen = false }
        val model = viewModel(rtspRepo(), preview, statusSource = statusSource, monitoring = monitoring)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()
        model.refreshStatus()
        testScheduler.runCurrent()

        assertEquals(
            "a live stream with totals must reach the service",
            MonitoringSample(StreamUiState.Live(1_000L), model.state.value.summary),
            monitoring.samples.last(),
        )

        preview.fail(StreamErrorKind.AuthFailed)
        testScheduler.runCurrent()

        assertEquals(
            "a dead stream must clear the notification totals too",
            MonitoringSample(StreamUiState.Failed(StreamErrorKind.AuthFailed), summary = null),
            monitoring.samples.last(),
        )
    }

    @Test
    fun the_backoff_stops_after_max_attempts_and_stays_failed() = runTest(dispatcher) {
        val preview = RecordingPreview().apply { autoPlayOnOpen = false }
        val model = viewModel(rtspRepo(), preview)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        repeat(ReconnectPolicy.MAX_ATTEMPTS) { index ->
            preview.fail(StreamErrorKind.Unreachable)
            testScheduler.runCurrent()
            assertEquals(StreamUiState.Reconnecting(index + 1, ReconnectPolicy.delayMillis(index + 1)), model.state.value.stream)
            testScheduler.advanceTimeBy(ReconnectPolicy.delayMillis(index + 1))
            testScheduler.runCurrent()
        }

        preview.fail(StreamErrorKind.Unreachable)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(120_000L)
        testScheduler.runCurrent()

        assertEquals(StreamUiState.Failed(StreamErrorKind.Unreachable), model.state.value.stream)
        assertEquals(1 + ReconnectPolicy.MAX_ATTEMPTS, preview.opened.size)
    }
}