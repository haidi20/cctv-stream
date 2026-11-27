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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeAnalysisTest {

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
        statusSource: EngineStatusSource,
        analysisSource: EngineAnalysisSource?,
    ) = HomeViewModel(
        repository = repo,
        session = id.warung.monitor.feature.cameras.session.InMemoryCameraSession(),
        statusSource = statusSource,
        preview = RecordingPreview(),
        clock = { 50_000L },
        pollInterval = HomePollInterval(0L),
        engineChanges = EngineChangeSignal { kotlinx.coroutines.flow.emptyFlow() },
        analysisSource = analysisSource,
    )

    @Test
    fun the_panel_shows_rust_rows_including_unknown_tables() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(
            listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream"))),
        )
        val model = viewModel(
            repo = repo,
            statusSource = MutableStatusSource(status()),
            analysisSource = MutableAnalysisSource(analysis()),
        )
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        model.refreshStatus()

        val state = model.state.value
        assertEquals(3, state.tables?.size)
        assertEquals(AnalysisStatus.Unknown, state.tables?.get(2)?.status)
        assertEquals(2, state.detections.size)
        assertEquals(2.0, state.inferenceFps, 0.001)
    }

    @Test
    fun the_frame_clock_and_delay_come_from_rust() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(
            listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream"))),
        )
        val model = viewModel(
            repo = repo,
            statusSource = MutableStatusSource(status()),
            analysisSource = MutableAnalysisSource(
                analysis(lastAnalysisAgeMillis = 500L, frameToInferenceMillis = 137L),
            ),
        )
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        model.refreshStatus()

        val state = model.state.value
        assertEquals(49_500L, state.analysisFrameAtMillis)
        assertEquals(137L, state.frameToInferenceMillis)
    }

    @Test
    fun a_missing_analysis_source_hides_the_panel_instead_of_showing_zeroes() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(
            listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream"))),
        )
        val model = viewModel(repo, MutableStatusSource(status()), analysisSource = null)
        testScheduler.runCurrent()

        assertNull("no source means no rows", model.state.value.tables)
    }

    @Test
    fun detail_is_not_read_from_another_cameras_session() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(
            listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream"))),
        )
        val analysisSource = MutableAnalysisSource(analysis())
        val model = viewModel(
            repo = repo,
            statusSource = MutableStatusSource(status(camera = "cam-2")),
            analysisSource = analysisSource,
        )
        testScheduler.runCurrent()

        assertNull(model.state.value.tables)
        assertEquals("a foreign camera id must skip the detail read", 0, analysisSource.reads)
    }

    @Test
    fun a_stopped_engine_does_not_keep_stale_rows_on_screen() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(
            listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream"))),
        )
        val status = MutableStatusSource(status())
        val source = MutableAnalysisSource(analysis())
        val model = viewModel(repo, status, source)
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        model.refreshStatus()
        assertTrue(model.state.value.tables?.isNotEmpty() == true)

        status.status = EngineStatus.Unavailable
        source.analysis = null
        model.refreshStatus()

        assertNull("a stopped engine must not leave stale table rows", model.state.value.tables)
        assertTrue(model.state.value.detections.isEmpty())
    }

    @Test
    fun the_demo_camera_never_reads_the_native_engine() = runTest(dispatcher) {
        val repo = InMemoryCameraRepository(listOf(profile()))
        val source = MutableAnalysisSource(analysis())
        val model = viewModel(repo, MutableStatusSource(status()), source)
        testScheduler.runCurrent()

        assertNull(model.state.value.tables)
        assertEquals(0, source.reads)
    }
}