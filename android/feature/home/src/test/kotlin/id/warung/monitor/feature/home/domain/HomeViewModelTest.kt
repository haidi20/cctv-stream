package id.warung.monitor.feature.home.domain

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.feature.cameras.data.InMemoryCameraRepository
import id.warung.monitor.feature.cameras.session.InMemoryCameraSession
import id.warung.monitor.feature.cameras.session.CameraSessionResult
import id.warung.monitor.feature.cameras.session.CameraSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

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
        repository: InMemoryCameraRepository,
        session: CameraSession = InMemoryCameraSession(),
        statusSource: EngineStatusSource = MutableStatusSource(),
        preview: RecordingPreview = RecordingPreview(),
        network: NetworkMonitor = MutableNetworkMonitor(),
    ) = HomeViewModel(
        repository = repository,
        session = session,
        statusSource = statusSource,
        preview = preview,
        clock = { 50_000L },
        pollInterval = HomePollInterval(0L),
        network = network,
    )

    @Test
    fun a_cellular_transport_blocks_the_rtsp_stream_and_shows_wrong_network() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(
            listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream"))),
        )
        val preview = RecordingPreview()
        val network = MutableNetworkMonitor(NetworkReach.CellularOnly)
        val model = viewModel(repo, preview = preview, network = network)
        testScheduler.runCurrent()

        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        assertEquals(StreamUiState.Failed(StreamErrorKind.WrongNetwork), model.state.value.stream)
        assertTrue("cellular must not open RTSP", preview.opened.isEmpty())
    }

    @Test
    fun wifi_returning_after_no_network_retries_the_stream_exactly_once() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(
            listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream"))),
        )
        val preview = RecordingPreview()
        val network = MutableNetworkMonitor(NetworkReach.Offline)
        val model = viewModel(repo, preview = preview, network = network)
        testScheduler.runCurrent()

        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()
        assertTrue(preview.opened.isEmpty())

        network.emit(NetworkReach.WifiPrivate)
        testScheduler.runCurrent()

        assertEquals(listOf("cam-1"), preview.opened)
        assertTrue(model.state.value.stream is StreamUiState.Connecting || model.state.value.stream is StreamUiState.Live)
    }

    @Test
    fun an_auth_failure_is_never_retried_when_wifi_returns() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(
            listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream"))),
        )
        val preview = RecordingPreview()
        val network = MutableNetworkMonitor()
        val model = viewModel(repo, preview = preview, network = network)
        testScheduler.runCurrent()

        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()
        preview.fail(StreamErrorKind.AuthFailed)
        testScheduler.runCurrent()
        val opensAfterFailure = preview.opened.size

        network.emit(NetworkReach.Offline)
        testScheduler.runCurrent()
        network.emit(NetworkReach.WifiPrivate)
        testScheduler.runCurrent()

        assertEquals("a 401 must not be retried", opensAfterFailure, preview.opened.size)
    }

    @Test
    fun the_demo_camera_never_shows_a_network_failure() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val network = MutableNetworkMonitor(NetworkReach.Offline)
        val model = viewModel(repo, network = network)
        testScheduler.runCurrent()

        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        assertTrue(model.state.value.stream !is StreamUiState.Failed)
    }

    @Test
    fun loading_the_list_does_not_open_a_camera_until_the_user_asks() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val preview = RecordingPreview()
        val model = viewModel(repo, preview = preview)
        testScheduler.runCurrent()

        assertEquals(profile().id, model.state.value.camera?.id)
        assertTrue(preview.opened.isEmpty())
        assertTrue(model.state.value.stream is StreamUiState.Idle)
    }

    @Test
    fun select_camera_opens_it_and_maps_engine_totals() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val session = InMemoryCameraSession()
        val preview = RecordingPreview()
        val statusSource = MutableStatusSource(status(camera = "cam-1"))
        val model = viewModel(repo, session, statusSource, preview)
        testScheduler.runCurrent()

        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()
        model.refreshStatus()
        testScheduler.runCurrent()

        assertEquals(listOf("cam-1"), preview.opened)
        assertEquals(CameraId("cam-1"), session.activeCameraId.value)
        val summary = model.state.value.summary
        assertNotNull(summary)
        assertEquals(3, summary?.available)
        assertEquals(2, summary?.occupied)
        assertEquals(1, summary?.unknown)
        assertEquals(5, summary?.known)
        assertEquals(6, summary?.total)
        assertEquals(50_000L, model.state.value.lastUpdatedMillis)
    }

    @Test
    fun unknown_is_reported_separately_from_available_and_occupied() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val statusSource = MutableStatusSource(status(available = 0, occupied = 0, unknown = 4))
        val model = viewModel(repo, statusSource = statusSource)
        testScheduler.runCurrent()
        model.refreshStatus()
        testScheduler.runCurrent()

        val summary = model.state.value.summary
        assertEquals(0, summary?.available)
        assertEquals(0, summary?.occupied)
        assertEquals(4, summary?.unknown)
        assertFalse(summary!!.known == summary.total)
    }

    @Test
    fun status_from_another_camera_never_becomes_the_selected_totals() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile("cam-1")))
        val statusSource = MutableStatusSource(status(camera = "cam-2"))
        val model = viewModel(repo, statusSource = statusSource)
        testScheduler.runCurrent()
        model.refreshStatus()
        testScheduler.runCurrent()

        assertNull(model.state.value.summary)
    }

    @Test
    fun a_failed_engine_clears_the_previous_numbers() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val statusSource = MutableStatusSource(status())
        val model = viewModel(repo, statusSource = statusSource)
        testScheduler.runCurrent()
        model.refreshStatus()
        testScheduler.runCurrent()
        assertNotNull(model.state.value.summary)

        statusSource.status = status(phase = EnginePhase.Failed)
        model.refreshStatus()
        testScheduler.runCurrent()

        assertNull(model.state.value.summary)
        assertTrue(model.state.value.summaryStale)
        assertNull(model.state.value.lastUpdatedMillis)
    }

    @Test
    fun an_identical_status_does_not_move_the_updated_clock() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val statusSource = MutableStatusSource(status())
        var now = 10_000L
        val model = HomeViewModel(
            repository = repo,
            session = InMemoryCameraSession(),
            statusSource = statusSource,
            preview = RecordingPreview(),
            clock = { now },
            pollInterval = HomePollInterval(0L),
        )
        testScheduler.runCurrent()
        model.refreshStatus()
        testScheduler.runCurrent()
        assertEquals(10_000L, model.state.value.lastUpdatedMillis)

        now = 99_000L
        model.refreshStatus()
        testScheduler.runCurrent()

        assertEquals(10_000L, model.state.value.lastUpdatedMillis)
    }

    @Test
    fun unavailable_engine_keeps_the_dash_and_marks_the_model_not_ready() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val statusSource = MutableStatusSource(EngineStatus.Unavailable)
        val model = viewModel(repo, statusSource = statusSource)
        testScheduler.runCurrent()
        model.refreshStatus()
        testScheduler.runCurrent()

        assertNull(model.state.value.summary)
        assertFalse(model.state.value.modelReady)
    }

    @Test
    fun retry_reopens_the_same_camera() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val preview = RecordingPreview()
        val model = viewModel(repo, preview = preview)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        model.retry()
        testScheduler.runCurrent()

        assertEquals(listOf("cam-1", "cam-1"), preview.opened)
    }

    @Test
    fun selecting_the_same_camera_twice_does_not_reopen_it() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val preview = RecordingPreview()
        val model = viewModel(repo, preview = preview)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        assertEquals(1, preview.opened.size)
    }

    @Test
    fun a_failed_session_shows_a_banner_and_does_not_open_the_preview() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val preview = RecordingPreview()
        val session = object : CameraSession {
            override val activeCameraId = kotlinx.coroutines.flow.MutableStateFlow<CameraId?>(null)
            override suspend fun open(cameraId: CameraId): CameraSessionResult =
                CameraSessionResult.Failed("Kamera sedang dipakai")
            override suspend fun close() = Unit
        }
        val model = viewModel(repo, session = session, preview = preview)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        assertTrue(preview.opened.isEmpty())
        assertEquals("Kamera sedang dipakai", model.state.value.banner)
        assertTrue(model.state.value.stream is StreamUiState.Failed)
    }

    @Test
    fun an_engine_that_has_not_seen_a_frame_does_not_erase_live_video() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val statusSource = MutableStatusSource(status(phase = EnginePhase.Connecting, framesIn = 0L))
        val model = viewModel(repo, statusSource = statusSource)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()
        assertTrue(model.state.value.stream is StreamUiState.Live)

        model.refreshStatus()
        testScheduler.runCurrent()

        assertTrue(model.state.value.stream is StreamUiState.Live)
    }

    @Test
    fun the_first_engine_frame_makes_connecting_authoritative() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val statusSource = MutableStatusSource(status(phase = EnginePhase.Connecting, framesIn = 0L))
        val model = viewModel(repo, statusSource = statusSource)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()

        statusSource.status = status(phase = EnginePhase.Connecting, framesIn = 1L)
        model.refreshStatus()
        testScheduler.runCurrent()

        assertTrue(model.state.value.stream is StreamUiState.Connecting)
    }

    @Test
    fun demo_camera_is_flagged_as_demo() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile(source = CameraSource.Demo)))
        val model = viewModel(repo)
        testScheduler.runCurrent()

        assertTrue(model.state.value.demoMode)
    }
}