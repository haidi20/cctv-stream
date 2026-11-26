package id.warung.monitor.engine

import android.graphics.Bitmap
import id.warung.monitor.core.EngineHealth
import id.warung.monitor.core.EngineInterface
import id.warung.monitor.core.StreamStatus
import id.warung.monitor.core.TableEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pump must stop feeding a preview that is no longer playing.
 *
 * A stopped Media3 player keeps its last texture, so `getBitmap()` would return
 * the same frame forever. Rust would then see "frames still arriving" and keep
 * the tables `occupied` long after the camera died. A second test pins the
 * dispatcher: inference runs from `LaunchedEffect`, which is Main, so the FFI
 * call must move to a background dispatcher or every frame would block the UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineFramePumpTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun the_loop_ends_at_the_first_tick_after_the_preview_stops_playing() = runTest {
        val pump = EngineFramePump(engineProvider = id.warung.monitor.di.EngineProvider())
        var playing = true
        var ticks = 0

        val job = launch {
            pump.run(
                cameraId = "cam-1",
                textureProvider = { null },
                keepRunning = {
                    ticks += 1
                    playing
                },
            )
        }
        runCurrent()
        advanceTimeBy(EngineFramePump.INTERVAL_MS + 1)
        runCurrent()
        playing = false
        advanceTimeBy(EngineFramePump.INTERVAL_MS * 5)
        runCurrent()

        assertEquals("the loop must not poll a dead preview forever", false, job.isActive)
        assertEquals(2, ticks)
    }

    @Test
    fun a_frame_is_pushed_off_the_main_thread() = runTest {
        val pump = EngineFramePump(engineProvider = id.warung.monitor.di.EngineProvider())
        val engine = RecordingEngine()
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)

        pump.pushFrameOffMain(engine, bitmap)

        assertTrue("pushFrame must be called", engine.pushed)
        assertFalse("inference must never run on Main", engine.onMainThread)
        assertEquals(4u, engine.width)
        assertEquals(2u, engine.height)
        assertEquals("RGB888 is three bytes per pixel", 4 * 2 * 3, engine.payloadSize)
    }

    private class RecordingEngine : EngineInterface {
        var pushed = false
        var onMainThread = false
        var width = 0u
        var height = 0u
        var payloadSize = 0

        override fun clearListeners() = Unit

        override fun health(): EngineHealth = throw UnsupportedOperationException("not used")

        override fun pushFrame(width: UInt, height: UInt, rgb: ByteArray): List<TableEvent> {
            pushed = true
            onMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            this.width = width
            this.height = height
            payloadSize = rgb.size
            return emptyList()
        }

        override fun reportStreamError(status: StreamStatus) = Unit

        override fun setTables(tablesJson: String) = Unit

        override fun start(config: id.warung.monitor.core.EngineConfig) = Unit

        override fun stop() = Unit

        override fun subscribe(listener: id.warung.monitor.core.EngineListener) = Unit
    }
}
