package id.warung.monitor.feature.home.domain

import id.warung.monitor.feature.cameras.data.InMemoryCameraRepository
import id.warung.monitor.feature.cameras.session.InMemoryCameraSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The offline note and the transient "back online" note are rendered straight
 * from state; the ViewModel owns the display window so the UI stays pure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeConnectivityTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(network: MutableNetworkMonitor) = HomeViewModel(
        repository = InMemoryCameraRepository(emptyList()),
        session = InMemoryCameraSession(),
        statusSource = MutableStatusSource(),
        preview = RecordingPreview(),
        clock = { 1_000L },
        pollInterval = HomePollInterval(0L),
        network = network,
    )

    @Test
    fun an_offline_transport_is_visible_before_any_camera_is_selected() = runTest(dispatcher) {
        val model = viewModel(MutableNetworkMonitor(NetworkReach.Offline))
        testScheduler.runCurrent()

        assertEquals(NetworkReach.Offline, model.state.value.network)
        assertFalse(model.state.value.justReconnected)
        assertNull("precondition: no camera is selected", model.state.value.camera)
    }

    @Test
    fun a_cellular_transport_is_visible_before_any_camera_is_selected() = runTest(dispatcher) {
        val model = viewModel(MutableNetworkMonitor(NetworkReach.CellularOnly))
        testScheduler.runCurrent()

        assertEquals(NetworkReach.CellularOnly, model.state.value.network)
        assertFalse(model.state.value.justReconnected)
    }

    @Test
    fun regaining_the_warung_wifi_shows_the_back_online_note() = runTest(dispatcher) {
        val network = MutableNetworkMonitor(NetworkReach.Offline)
        val model = viewModel(network)
        testScheduler.runCurrent()

        network.emit(NetworkReach.WifiPrivate)
        testScheduler.runCurrent()

        assertEquals(NetworkReach.WifiPrivate, model.state.value.network)
        assertTrue(model.state.value.justReconnected)
    }

    @Test
    fun the_back_online_note_clears_itself_after_the_display_window() = runTest(dispatcher) {
        val network = MutableNetworkMonitor(NetworkReach.Offline)
        val model = viewModel(network)
        testScheduler.runCurrent()

        network.emit(NetworkReach.WifiPrivate)
        testScheduler.runCurrent()
        assertTrue(model.state.value.justReconnected)

        testScheduler.advanceTimeBy(HomeViewModel.RECONNECTED_VISIBLE_MS + 1)
        testScheduler.runCurrent()

        assertFalse(model.state.value.justReconnected)
        assertEquals("the transport itself must stay online", NetworkReach.WifiPrivate, model.state.value.network)
    }

    @Test
    fun the_first_wifi_emission_does_not_show_a_back_online_note() = runTest(dispatcher) {
        val model = viewModel(MutableNetworkMonitor(NetworkReach.WifiPrivate))
        testScheduler.runCurrent()

        assertEquals(NetworkReach.WifiPrivate, model.state.value.network)
        assertFalse("already online is not a reconnection", model.state.value.justReconnected)
    }

    @Test
    fun the_note_can_appear_again_after_a_later_reconnection() = runTest(dispatcher) {
        val network = MutableNetworkMonitor(NetworkReach.Offline)
        val model = viewModel(network)
        testScheduler.runCurrent()

        network.emit(NetworkReach.WifiPrivate)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(HomeViewModel.RECONNECTED_VISIBLE_MS + 1)
        testScheduler.runCurrent()
        assertFalse(model.state.value.justReconnected)

        network.emit(NetworkReach.CellularOnly)
        testScheduler.runCurrent()
        network.emit(NetworkReach.WifiPrivate)
        testScheduler.runCurrent()

        assertTrue("a second regain is a new reconnection", model.state.value.justReconnected)
    }
}