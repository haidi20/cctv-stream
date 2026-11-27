package id.warung.monitor.feature.cameras.ui

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.network.ConnectionLayer
import id.warung.monitor.core.network.ConnectionReport
import id.warung.monitor.core.network.LayerOutcome
import id.warung.monitor.core.network.RtspEndpoint
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

    private fun viewModel(
        repository: InMemoryCameraRepository = InMemoryCameraRepository(),
        connection: ConnectionTestSource = ConnectionTestSource { ConnectionReport.NotStarted },
    ) = CameraListViewModel(
        repository = repository,
        idFactory = { CameraId("cam-${nextId++}") },
        connectionTest = connection,
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
    fun a_dev_video_camera_is_never_opened_in_the_rtsp_editor() = runTest(dispatcher) {
        val video = CameraProfile(
            id = CameraId("cctv-simulasi"),
            label = CameraLabel("Simulasi CCTV"),
            source = CameraSource.Video("clip.mp4"),
            createdAt = 1L,
            updatedAt = 1L,
        )
        val repository = InMemoryCameraRepository(listOf(video))
        val model = viewModel(repository)

        model.startEdit(video.id)

        assertNull("the sheet would rewrite the source", model.state.value.editing)
        assertEquals("Kamera simulasi tidak bisa diubah", model.state.value.message)
        assertTrue(repository.all().single().source is CameraSource.Video)
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
    fun a_connection_test_passes_the_typed_secret_and_reports_every_layer() = runTest(dispatcher) {
        var seenSecret: String? = null
        var seenHost: String? = null
        val report = ConnectionReport(
            ConnectionLayer.entries.associateWith { LayerOutcome.Passed },
        )
        val model = viewModel(
            connection = ConnectionTestSource { endpoint ->
                // The ViewModel owns and wipes the array, so read it while the
                // probe is still running.
                seenSecret = endpoint.secret?.let(::String)
                seenHost = endpoint.host
                report
            },
        )

        model.startAdd()
        model.updateSourceType(CameraSourceType.Rtsp)
        model.updateHost("192.168.1.64")
        model.updatePort("554")
        model.updatePath("/h264/ch1/sub/av_stream")
        model.updateUsername("admin")
        model.updatePassword("hunter2")
        model.testConnection()

        assertEquals("hunter2", seenSecret)
        assertEquals("192.168.1.64", seenHost)
        assertEquals(ConnectionTestUiState.Done(report), model.state.value.editing?.connection)
    }

    @Test
    fun the_typed_password_is_wiped_and_never_persisted() = runTest(dispatcher) {
        val repository = InMemoryCameraRepository()
        var captured: CharArray? = null
        val model = viewModel(
            repository = repository,
            connection = ConnectionTestSource { endpoint ->
                captured = endpoint.secret
                ConnectionReport.NotStarted
            },
        )
        model.startAdd()
        model.updateLabel("Depan")
        model.updateSourceType(CameraSourceType.Rtsp)
        model.updateHost("192.168.1.64")
        model.updatePort("554")
        model.updatePath("/h264/ch1/sub/av_stream")
        model.updateUsername("admin")
        model.updatePassword("hunter2")

        model.testConnection()

        assertTrue("the ViewModel must wipe the array after the test", captured!!.all { it == '\u0000' })
        model.saveEditor()
        assertTrue("password must not reach the profile", !repository.all().single().toString().contains("hunter2"))
    }

    @Test
    fun a_throwing_probe_keeps_the_editor_open_with_an_error_and_no_report() = runTest(dispatcher) {
        val model = viewModel(connection = ConnectionTestSource { error("socket denied") })
        model.startAdd()
        model.updateSourceType(CameraSourceType.Rtsp)
        model.updateHost("192.168.1.64")
        model.updatePort("554")
        model.updatePath("/h264/ch1/sub/av_stream")

        model.testConnection()

        assertEquals(ConnectionTestUiState.Idle, model.state.value.editing?.connection)
        assertNotNull(model.state.value.editing?.error)
        assertTrue(!(model.state.value.editing?.error ?: "").contains("socket denied"))
    }

    @Test
    fun a_connection_test_on_the_demo_source_is_a_no_op() = runTest(dispatcher) {
        var called = 0
        val model = viewModel(connection = ConnectionTestSource { called++; ConnectionReport.NotStarted })
        model.startAdd()
        model.updateLabel("Demo")
        model.updateSourceType(CameraSourceType.Demo)

        model.testConnection()

        assertEquals(0, called)
        assertEquals(ConnectionTestUiState.Idle, model.state.value.editing?.connection)
    }

    @Test
    fun opening_a_camera_records_the_timestamp() = runTest(dispatcher) {
        val repository = InMemoryCameraRepository(listOf(profile()))
        val model = viewModel(repository)

        model.markOpened(CameraId("cam-1"))

        assertNotNull(repository.find(CameraId("cam-1"))?.lastOpenedAt)
    }
}