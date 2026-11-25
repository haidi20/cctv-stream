package id.warung.monitor.feature.cameras.ui

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraListViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private var nextId = 1

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(repository: InMemoryCameraRepository = InMemoryCameraRepository()) =
        CameraListViewModel(
            repository = repository,
            idFactory = { CameraId("cam-${nextId++}") },
        )

    private fun profile(id: String = "cam-1", label: String = "Lama") = CameraProfile(
        id = CameraId(id),
        label = CameraLabel(label),
        source = CameraSource.Demo,
        createdAt = 5L,
        updatedAt = 5L,
    )

    @Test
    fun a_new_camera_gets_a_generated_id_and_is_persisted() = runTest(dispatcher) {
        val repository = InMemoryCameraRepository()
        val model = viewModel(repository)

        model.startAdd()
        model.updateLabel("Warung Depan")
        model.saveEditor()

        val saved = repository.all().single()
        assertEquals("cam-1", saved.id.value)
        assertEquals("Warung Depan", saved.label.value)
        assertTrue(saved.source is CameraSource.Demo)
        assertNull(model.state.value.editing)
    }

    @Test
    fun editing_a_camera_keeps_its_id_and_changes_only_the_label() = runTest(dispatcher) {
        val repository = InMemoryCameraRepository(listOf(profile("cam-9")))
        val model = viewModel(repository)

        model.startEdit(CameraId("cam-9"))
        model.updateLabel("Baru")
        model.saveEditor()

        assertEquals("cam-9", repository.all().single().id.value)
        assertEquals("Baru", repository.all().single().label.value)
    }

    @Test
    fun an_invalid_rtsp_endpoint_keeps_the_dialog_open_with_an_error() = runTest(dispatcher) {
        val repository = InMemoryCameraRepository()
        val model = viewModel(repository)

        model.startAdd()
        model.updateLabel("Belakang")
        model.updateSourceType(CameraSourceType.Rtsp)
        model.updateHost("rtsp://user:pass@192.168.1.64")
        model.updatePort("554")
        model.saveEditor()

        assertNotNull(model.state.value.editing)
        assertNotNull(model.state.value.editing?.error)
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun rtsp_fields_are_validated_and_saved_without_a_password() = runTest(dispatcher) {
        val repository = InMemoryCameraRepository()
        val model = viewModel(repository)

        model.startAdd()
        model.updateLabel("Belakang")
        model.updateSourceType(CameraSourceType.Rtsp)
        model.updateHost("192.168.1.64")
        model.updatePort("554")
        model.updatePath("/h264/ch1/sub/av_stream")
        model.updateUsername("viewer")
        model.saveEditor()

        val source = repository.all().single().source
        assertTrue(source is CameraSource.Rtsp)
        assertEquals("192.168.1.64", (source as CameraSource.Rtsp).host)
        assertEquals(554, source.port)
        assertEquals("viewer", source.username)
    }

    @Test
    fun deletion_requires_confirmation_before_removing_a_camera() = runTest(dispatcher) {
        val repository = InMemoryCameraRepository(listOf(profile()))
        val model = viewModel(repository)

        model.requestDelete(CameraId("cam-1"))
        assertEquals(1, repository.size())

        model.confirmDelete()
        assertEquals(0, repository.size())
    }

    @Test
    fun port_input_is_digits_only() = runTest(dispatcher) {
        val model = viewModel()
        model.startAdd()
        model.updateSourceType(CameraSourceType.Rtsp)
        model.updatePort("5a5b4")
        assertEquals("554", model.state.value.editing?.port)
    }

    @Test
    fun opening_a_camera_records_the_timestamp() = runTest(dispatcher) {
        val repository = InMemoryCameraRepository(listOf(profile()))
        val model = viewModel(repository)

        model.markOpened(CameraId("cam-1"))

        assertNotNull(repository.find(CameraId("cam-1"))?.lastOpenedAt)
    }
}