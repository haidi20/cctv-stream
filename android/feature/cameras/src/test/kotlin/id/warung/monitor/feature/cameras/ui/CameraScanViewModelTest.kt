package id.warung.monitor.feature.cameras.ui

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraDeviceId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.network.DiscoveredCamera
import id.warung.monitor.core.network.DiscoverySource
import id.warung.monitor.core.network.ScanOutcome
import id.warung.monitor.core.network.ScoredCamera
import id.warung.monitor.feature.cameras.data.InMemoryCameraRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class CameraScanViewModelTest {

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
        repository: InMemoryCameraRepository = InMemoryCameraRepository(),
        source: CameraScanSource,
    ) = CameraScanViewModel(repository = repository, source = source)

    private fun rtspProfile(host: String, port: Int = 554) = CameraProfile(
        id = CameraId("cam-1"),
        label = CameraLabel("Depan"),
        source = CameraSource.Rtsp(host = host, port = port, path = "/live"),
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun savedUuidProfile(host: String): CameraProfile = rtspProfile(host).copy(
        deviceId = CameraDeviceId.from("3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
    )

    private fun found(vararg cameras: DiscoveredCamera, fromCache: Boolean = false) =
        ScanOutcome.Found(cameras.map { ScoredCamera(it, DiscoverySource.OpenPort) }, fromCache)

    @Test
    fun no_wifi_is_reported_and_discards_any_previous_results() = runTest(dispatcher) {
        val model = viewModel(source = CameraScanSource { ScanOutcome.NoWifi })

        model.start()

        assertEquals(ScanUiState.NoWifi, model.state.value)
    }

    @Test
    fun results_are_mapped_to_rows_and_mark_already_saved_cameras() = runTest(dispatcher) {
        val repository = InMemoryCameraRepository(listOf(rtspProfile("192.168.1.5")))
        val model = viewModel(
            repository = repository,
            source = CameraScanSource {
                found(
                    DiscoveredCamera("192.168.1.5", 554),
                    DiscoveredCamera("192.168.1.9", 554, brand = "EZVIZ"),
                )
            },
        )

        model.start()

        val results = model.state.value as ScanUiState.Results
        assertEquals(listOf("192.168.1.5:554", "192.168.1.9:554"), results.rows.map { it.endpoint })
        assertTrue(results.rows.first().alreadySaved)
        assertFalse(results.rows.last().alreadySaved)
        assertEquals("EZVIZ - port terbuka", results.rows.last().subtitle)
    }

    @Test
    fun a_camera_the_user_saved_is_passed_to_discovery_so_it_ranks_first() = runTest(dispatcher) {
        var seen: List<DiscoveredCamera> = emptyList()
        val repository = InMemoryCameraRepository(listOf(rtspProfile("192.168.1.77", 8554)))
        val model = viewModel(
            repository = repository,
            source = CameraScanSource { saved ->
                seen = saved
                ScanOutcome.Found(emptyList(), fromCache = false)
            },
        )

        model.start()

        assertEquals(listOf("192.168.1.77:8554"), seen.map { it.endpoint })
    }

    @Test
    fun a_saved_camera_with_a_new_ip_is_still_marked_as_saved_by_its_uuid() = runTest(dispatcher) {
        val repository = InMemoryCameraRepository(listOf(savedUuidProfile("192.168.1.5")))
        val model = viewModel(
            repository = repository,
            source = CameraScanSource {
                found(
                    DiscoveredCamera(
                        "192.168.1.42",
                        554,
                        deviceId = CameraDeviceId.from("3F2504E0-4F89-11D3-9A0C-0305E82C3301"),
                    ),
                )
            },
        )

        model.start()

        val results = model.state.value as ScanUiState.Results
        assertEquals("192.168.1.42:554", results.rows.single().endpoint)
        assertTrue(results.rows.single().alreadySaved)
    }

    @Test
    fun a_cached_result_is_flagged_so_the_ui_can_say_so() = runTest(dispatcher) {
        val model = viewModel(
            source = CameraScanSource { found(DiscoveredCamera("192.168.1.5", 554), fromCache = true) },
        )

        model.start()

        assertTrue((model.state.value as ScanUiState.Results).fromCache)
    }

    @Test
    fun a_throwing_source_becomes_a_message_not_a_crash() = runTest(dispatcher) {
        val model = viewModel(source = CameraScanSource { error("socket exploded") })

        model.start()

        assertTrue(model.state.value is ScanUiState.Failed)
    }

    @Test
    fun an_empty_result_is_a_results_state_not_an_error() = runTest(dispatcher) {
        val model = viewModel(source = CameraScanSource { ScanOutcome.Found(emptyList(), fromCache = false) })

        model.start()

        val results = model.state.value as ScanUiState.Results
        assertTrue(results.isEmpty)
    }

    @Test
    fun only_one_scan_runs_at_a_time_and_the_previous_one_is_cancelled() = runTest(dispatcher) {
        var starts = 0
        val gate = CompletableDeferred<ScanOutcome>()
        val model = viewModel(
            source = CameraScanSource {
                starts++
                gate.await()
            },
        )

        model.start()
        model.start()

        assertEquals(2, starts)
        assertEquals(ScanUiState.Scanning, model.state.value)
    }

    @Test
    fun dismissing_the_sheet_cancels_in_flight_work_and_resets_state() = runTest(dispatcher) {
        var completed = false
        val gate = CompletableDeferred<ScanOutcome>()
        val model = viewModel(
            source = CameraScanSource {
                try {
                    gate.await()
                } finally {
                    completed = true
                }
            },
        )

        model.start()
        model.cancel()
        gate.complete(ScanOutcome.NoWifi)

        assertTrue("the in-flight scan must be cancelled", completed)
        assertEquals(ScanUiState.Idle, model.state.value)
    }
}
