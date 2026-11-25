package id.warung.monitor.feature.tables.ui

import id.warung.monitor.core.model.MappingFrame
import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.TableId
import id.warung.monitor.feature.tables.data.InMemoryTableConfigRepository
import id.warung.monitor.feature.tables.engine.TableApplyResult
import id.warung.monitor.feature.tables.engine.TableConfigSink
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
class TableMappingViewModelTest {

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
        repository: InMemoryTableConfigRepository = InMemoryTableConfigRepository(),
        sink: TableConfigSink = TableConfigSink { _, _ -> TableApplyResult.Applied },
    ): TableMappingViewModel = TableMappingViewModel(repository, sink).also {
        it.bindCamera("cam-depan", "Warung Depan")
    }

    private fun TableMappingViewModel.addSquare(label: String = "Meja 01") {
        addTable()
        val id = state.value.drawingTableId ?: error("no drawing table")
        listOf(
            NormalizedPoint(0.2, 0.2),
            NormalizedPoint(0.4, 0.2),
            NormalizedPoint(0.4, 0.4),
            NormalizedPoint(0.2, 0.4),
        ).forEach { addVertex(it) }
        updateLabel(id, label)
        finishDrawing()
    }

    @Test
    fun adding_a_table_creates_the_next_stable_id() = runTest(dispatcher) {
        val model = viewModel()
        model.addTable()
        assertEquals("meja-01", model.state.value.tables.single().id.value)
        assertEquals("meja-01", model.state.value.drawingTableId?.value)
    }

    @Test
    fun saved_map_is_loaded_on_start() = runTest(dispatcher) {
        val repository = InMemoryTableConfigRepository()
        val first = viewModel(repository)
        first.addSquare()
        first.save()
        val second = viewModel(repository)
        second.load()
        assertEquals(1, second.state.value.tables.size)
        assertEquals(1L, second.state.value.lastSavedRevision)
    }

    @Test
    fun save_requires_at_least_one_table() = runTest(dispatcher) {
        val model = viewModel()
        model.save()
        assertNull(model.state.value.lastSavedRevision)
        assertTrue(model.state.value.message?.contains("minimal satu meja") == true)
    }

    @Test
    fun save_is_blocked_when_any_table_is_incomplete() = runTest(dispatcher) {
        var sinkCalls = 0
        val model = viewModel(sink = { _, _ -> sinkCalls++; TableApplyResult.Applied })
        model.addSquare()
        model.addTable()
        model.save()
        assertEquals(0, sinkCalls)
        assertNull(model.state.value.lastSavedRevision)
    }

    @Test
    fun save_writes_room_before_calling_the_engine() = runTest(dispatcher) {
        val order = mutableListOf<String>()
        val repository = object : id.warung.monitor.feature.tables.data.TableConfigRepository {
            private var stored: id.warung.monitor.feature.tables.data.StoredTableConfig? = null
            override suspend fun active(cameraId: String) = stored
            override fun observeActive(cameraId: String) =
                kotlinx.coroutines.flow.flowOf(stored)
            override suspend fun save(
                cameraId: String,
                config: id.warung.monitor.core.model.TableConfigSet,
            ): id.warung.monitor.feature.tables.data.StoredTableConfig {
                order += "room"
                return id.warung.monitor.feature.tables.data.StoredTableConfig(
                    cameraId, 1, config, 0L,
                ).also { stored = it }
            }
        }
        val model = TableMappingViewModel(repository) { _, _ -> order += "engine"; TableApplyResult.Applied }
        model.bindCamera("cam-depan", "Warung Depan")
        model.addSquare()
        model.save()
        assertEquals(listOf("room", "engine"), order)
        assertEquals(TableApplyResult.Applied, model.state.value.engineResult)
    }

    @Test
    fun engine_failure_keeps_the_local_revision() = runTest(dispatcher) {
        val model = viewModel(sink = { _, _ -> TableApplyResult.Unavailable("no so") })
        model.addSquare()
        model.save()
        assertNotNull(model.state.value.lastSavedRevision)
        assertTrue(model.state.value.engineResult is TableApplyResult.Unavailable)
        assertTrue(model.state.value.message?.contains("tersimpan lokal") == true)
    }

    @Test
    fun engine_rejection_is_reported_without_losing_the_map() = runTest(dispatcher) {
        val model = viewModel(sink = { _, _ -> TableApplyResult.Rejected("bad polygon") })
        model.addSquare()
        model.save()
        assertEquals(1, model.state.value.tables.size)
        assertNotNull(model.state.value.lastSavedRevision)
        assertTrue(model.state.value.engineResult is TableApplyResult.Rejected)
    }

    @Test
    fun undo_restores_the_previous_vertices() = runTest(dispatcher) {
        val model = viewModel()
        model.addTable()
        val id = model.state.value.drawingTableId!!
        model.addVertex(NormalizedPoint(0.2, 0.2))
        model.addVertex(NormalizedPoint(0.4, 0.2))
        val before = model.state.value.tables.single().points.size
        model.undo()
        assertEquals(before - 1, model.state.value.tables.single().points.size)
        model.redo()
        assertEquals(before, model.state.value.tables.single().points.size)
        assertEquals(id, model.state.value.drawingTableId)
    }

    @Test
    fun undo_history_is_bounded_to_fifty_snapshots() = runTest(dispatcher) {
        val model = viewModel()
        model.addTable()
        val id = model.state.value.tables.single().id
        repeat(80) { model.updateLabel(id, "label-$it") }
        repeat(55) { model.undo() }
        assertFalse(model.state.value.canUndo)
        assertTrue(model.state.value.canRedo)
    }

    @Test
    fun deleting_a_selected_table_clears_the_selection() = runTest(dispatcher) {
        val model = viewModel()
        model.addSquare()
        val id = TableId("meja-01")
        model.selectTable(id)
        model.deleteTable(id)
        assertTrue(model.state.value.tables.isEmpty())
        assertNull(model.state.value.selectedTableId)
    }

    @Test
    fun recalibration_is_detected_from_the_saved_frame() = runTest(dispatcher) {
        val model = viewModel()
        model.addSquare()
        model.save()
        model.setLiveFrame(640, 480, "4:3")
        assertTrue(model.state.value.needsRecalibration)
        model.setLiveFrame(1920, 1080, "16:9")
        assertFalse(model.state.value.needsRecalibration)
    }

    @Test
    fun invalid_label_is_trimmed_to_forty_characters() = runTest(dispatcher) {
        val model = viewModel()
        model.addTable()
        val id = model.state.value.tables.single().id
        model.updateLabel(id, "x".repeat(80))
        assertEquals(40, model.state.value.tables.single().label.length)
    }

    @Test
    fun maps_are_isolated_per_camera() = runTest(dispatcher) {
        val repository = InMemoryTableConfigRepository()
        val model = viewModel(repository)
        model.addSquare(label = "Meja Depan")
        model.save()

        model.bindCamera("cam-belakang", "Warung Belakang")
        assertTrue(model.state.value.tables.isEmpty())
        model.addSquare(label = "Meja Belakang")
        model.save()

        model.bindCamera("cam-depan", "Warung Depan")
        assertEquals(listOf("Meja Depan"), model.state.value.tables.map { it.label })
    }

    @Test
    fun save_without_a_bound_camera_is_rejected() = runTest(dispatcher) {
        val sinkCalls = mutableListOf<String>()
        val model = TableMappingViewModel(InMemoryTableConfigRepository()) { cameraId, _ ->
            sinkCalls += cameraId
            TableApplyResult.Applied
        }
        model.addSquare()
        model.save()
        assertTrue(model.state.value.message?.contains("Pilih kamera") == true)
        assertNull(model.state.value.lastSavedRevision)
        assertTrue(sinkCalls.isEmpty())
    }

    @Test
    fun sink_receives_the_bound_camera_id() = runTest(dispatcher) {
        val appliedTo = mutableListOf<String>()
        val model = TableMappingViewModel(InMemoryTableConfigRepository()) { cameraId, _ ->
            appliedTo += cameraId
            TableApplyResult.Applied
        }
        model.bindCamera("cam-belakang", "Warung Belakang")
        model.addSquare()
        model.save()
        assertEquals(listOf("cam-belakang"), appliedTo)
        assertEquals("Warung Belakang", model.state.value.cameraLabel)
    }

    @Test
    fun map_for_a_camera_that_is_not_active_is_kept_locally() = runTest(dispatcher) {
        val model = viewModel(sink = { _, _ -> TableApplyResult.WrongCamera("cam-lain") })
        model.addSquare()
        model.save()
        assertEquals(1, model.state.value.tables.size)
        assertNotNull(model.state.value.lastSavedRevision)
        assertTrue(model.state.value.engineResult is TableApplyResult.WrongCamera)
    }

    @Test
    fun live_frame_update_keeps_existing_geometry() = runTest(dispatcher) {
        val model = viewModel()
        model.addSquare()
        val before = model.state.value.tables.single().points
        model.setLiveFrame(1920, 1080, "16:9")
        assertEquals(before, model.state.value.tables.single().points)
        assertEquals(MappingFrame(1920, 1080, "16:9"), model.state.value.frame)
    }
}