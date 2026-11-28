package id.warung.monitor.feature.home.domain

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.feature.cameras.data.InMemoryCameraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Analysis is only meaningful once the camera has a saved polygon map, so Home
 * asks Room first and shows a mapping prompt instead of an empty panel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeMappingTest {

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
        mappingSource: TableMappingSource?,
    ) = HomeViewModel(
        repository = repo,
        session = id.warung.monitor.feature.cameras.session.InMemoryCameraSession(),
        statusSource = MutableStatusSource(status()),
        preview = RecordingPreview(),
        clock = { 50_000L },
        pollInterval = HomePollInterval(0L),
        engineChanges = EngineChangeSignal { kotlinx.coroutines.flow.emptyFlow() },
        mappingSource = mappingSource ?: TableMappingSource { kotlinx.coroutines.flow.emptyFlow() },
    )

    private fun rtspRepo() = InMemoryCameraRepository(
        listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream"))),
    )

    @Test
    fun a_camera_without_a_saved_map_asks_for_mapping() = runTest(dispatcher) {
        val model = viewModel(rtspRepo(), FakeTableMappingSource())
        testScheduler.runCurrent()

        assertEquals(TableMapState.Missing, model.state.value.mapping)
        assertTrue(model.state.value.needsMapping)
    }

    @Test
    fun analysis_cannot_be_enabled_before_the_camera_has_a_map() = runTest(dispatcher) {
        val model = viewModel(rtspRepo(), FakeTableMappingSource(mapOf("cam-1" to null)))
        testScheduler.runCurrent()
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()
        assertTrue(model.state.value.needsMapping)
        testScheduler.runCurrent()
        model.setAnalysisEnabled(false)
        testScheduler.runCurrent()
        assertFalse("precondition: analysis was off", model.state.value.analysisEnabled)

        model.setAnalysisEnabled(true)
        testScheduler.runCurrent()

        assertFalse("unmapped camera must not run inference", model.state.value.analysisEnabled)
    }
    @Test
    fun a_saved_map_unlocks_the_analysis_section() = runTest(dispatcher) {
        val source = FakeTableMappingSource(
            mapOf("cam-1" to SavedTableMap(tables = 4, revision = 2)),
        )
        val model = viewModel(rtspRepo(), source)
        testScheduler.runCurrent()

        assertEquals(TableMapState.Mapped(tables = 4, revision = 2), model.state.value.mapping)
        assertFalse(model.state.value.needsMapping)
    }

    @Test
    fun a_revision_without_polygons_still_counts_as_missing() = runTest(dispatcher) {
        val source = FakeTableMappingSource(
            mapOf("cam-1" to SavedTableMap(tables = 0, revision = 3)),
        )
        val model = viewModel(rtspRepo(), source)
        testScheduler.runCurrent()

        assertEquals(TableMapState.Missing, model.state.value.mapping)
    }

    @Test
    fun a_redrawn_map_updates_the_gate_without_a_reselect() = runTest(dispatcher) {
        val source = FakeTableMappingSource()
        val model = viewModel(rtspRepo(), source)
        testScheduler.runCurrent()
        assertTrue(model.state.value.needsMapping)

        source.emit("cam-1", SavedTableMap(tables = 2, revision = 1))
        testScheduler.runCurrent()

        assertEquals(TableMapState.Mapped(tables = 2, revision = 1), model.state.value.mapping)
    }

    @Test
    fun switching_cameras_reads_the_new_cameras_map() = runTest(dispatcher) {
        val second = profile(
            id = "cam-2",
            label = "Kamera Kedua",
            source = CameraSource.Rtsp("192.168.1.65", 554, "/stream"),
        )
        val repo = InMemoryCameraRepository(
            listOf(profile(source = CameraSource.Rtsp("192.168.1.64", 554, "/stream")), second),
        )
        val source = FakeTableMappingSource(
            mapOf(
                "cam-1" to SavedTableMap(tables = 4, revision = 1),
                "cam-2" to null,
            ),
        )
        val model = viewModel(repo, source)
        testScheduler.runCurrent()
        // The list is label-sorted, so select the camera under test explicitly
        // instead of relying on which profile happens to be first.
        model.selectCamera(CameraId("cam-1"))
        testScheduler.runCurrent()
        assertEquals(TableMapState.Mapped(tables = 4, revision = 1), model.state.value.mapping)

        model.selectCamera(CameraId("cam-2"))
        testScheduler.runCurrent()

        assertEquals(TableMapState.Missing, model.state.value.mapping)
        assertTrue(source.observed.contains("cam-2"))
    }

    @Test
    fun an_unwired_mapping_source_never_blocks_the_analysis_section() = runTest(dispatcher) {
        val model = viewModel(rtspRepo(), mappingSource = null)
        testScheduler.runCurrent()

        assertEquals(TableMapState.Unknown, model.state.value.mapping)
        assertFalse("a missing repository must not fake 'belum ada peta'", model.state.value.needsMapping)
    }

    @Test
    fun a_failed_map_read_is_not_reported_as_missing() = runTest(dispatcher) {
        val broken = TableMappingSource { flow { throw IllegalStateException("room unavailable") } }
        val model = viewModel(rtspRepo(), broken)
        testScheduler.runCurrent()

        assertEquals(TableMapState.Unknown, model.state.value.mapping)
        assertFalse(model.state.value.needsMapping)
    }
}
