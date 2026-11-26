package id.warung.monitor.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.feature.home.domain.CameraPreviewCoordinator
import id.warung.monitor.feature.home.domain.PreviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One contract, run against the fake Home uses and the real Media3 coordinator.
 *
 * Home derives live/failed from PreviewState, so both implementations must
 * agree on the sequence Idle -> Playing -> Idle. The Media3 half uses a Demo
 * source: building a player needs no camera and no network under Robolectric.
 * Real RTSP playback is still device-only evidence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class PreviewCoordinatorContractTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun implementations(): List<Pair<String, CameraPreviewCoordinator>> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return listOf(
            "fake" to FakePreview(),
            "media3" to Media3CameraPreview(context),
        )
    }

    @Test
    fun a_fresh_coordinator_starts_idle() = runTest {
        for ((name, preview) in implementations()) {
            assertEquals("$name must start idle", PreviewState.Idle, preview.state.value)
        }
    }

    @Test
    fun opening_a_demo_camera_reaches_playing() = runTest {
        for ((name, preview) in implementations()) {
            preview.open(camera("cam-a"), secret = null)
            assertEquals("$name must play a demo source", PreviewState.Playing, preview.state.value)
        }
    }

    @Test
    fun closing_returns_to_idle() = runTest {
        for ((name, preview) in implementations()) {
            preview.open(camera("cam-a"), secret = null)
            preview.close()
            assertEquals("$name must be idle after close", PreviewState.Idle, preview.state.value)
        }
    }

    @Test
    fun a_second_open_after_close_plays_again() = runTest {
        for ((name, preview) in implementations()) {
            preview.open(camera("cam-a"), secret = null)
            preview.close()
            preview.open(camera("cam-b"), secret = null)
            assertEquals("$name must replay after close", PreviewState.Playing, preview.state.value)
        }
    }

    @Test
    fun opening_another_camera_replaces_the_first_without_a_close() = runTest {
        for ((name, preview) in implementations()) {
            preview.open(camera("cam-a"), secret = null)
            preview.open(camera("cam-b"), secret = null)
            assertEquals("$name must stay playing", PreviewState.Playing, preview.state.value)
        }
    }

    @Test
    fun the_password_is_wiped_after_open_returns() = runTest {
        for ((name, preview) in implementations()) {
            val secret = "rahasia".toCharArray()
            preview.open(camera("cam-a"), secret)
            assertTrue(
                "$name must clear the caller-owned secret",
                secret.all { it == Char(0) },
            )
        }
    }

    private fun camera(id: String) = CameraProfile(
        id = CameraId(id),
        label = CameraLabel("Kamera $id"),
        source = CameraSource.Demo,
        createdAt = 1L,
        updatedAt = 1L,
    )

    /** Minimal stand-in for the fake in feature:home, kept local on purpose. */
    private class FakePreview : CameraPreviewCoordinator {
        private val mutable = MutableStateFlow<PreviewState>(PreviewState.Idle)
        override val state: StateFlow<PreviewState> = mutable

        override suspend fun open(camera: CameraProfile, secret: CharArray?) {
            mutable.value = PreviewState.Playing
            secret?.fill(Char(0))
        }

        override suspend fun close() {
            mutable.value = PreviewState.Idle
        }
    }
}
