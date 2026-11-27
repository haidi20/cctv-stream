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
import org.junit.Assert.assertSame
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
        val pump = EngineFramePump(engineProvider = id.warung.monitor.di.EngineProvider(), cadence = InferenceCadence(), loadSource = CoolPhone, budget = InferenceBudget())
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
        // The first check runs immediately (fixed-rate pacing, no initial
        // sleep), one more at ~500 ms, and the check at ~1000 ms ends the loop.
        assertEquals("no further polling after Playing is lost", 3, ticks)
    }

    @Test
    fun a_missing_engine_does_not_turn_the_loop_into_a_busy_spin() = runTest {
        val pump = EngineFramePump(engineProvider = id.warung.monitor.di.EngineProvider(), cadence = InferenceCadence(), loadSource = CoolPhone, budget = InferenceBudget())
        var ticks = 0
        val job = launch {
            pump.run(
                cameraId = "cam-1",
                textureProvider = { null },
                keepRunning = {
                    ticks += 1
                    true
                },
            )
        }
        runCurrent()
        advanceTimeBy(EngineFramePump.INTERVAL_MS * 4)
        runCurrent()
        job.cancel()
        // With a 500 ms pace, four intervals allow at most five checks. A
        // skipped wait would spin without bound and this test would hang.
        assertEquals("the loop must wait even when nothing is ready", 5, ticks)
    }

    @Test
    fun a_frame_is_pushed_off_the_main_thread() = runTest {
        val pump = EngineFramePump(engineProvider = id.warung.monitor.di.EngineProvider(), cadence = InferenceCadence(), loadSource = CoolPhone, budget = InferenceBudget())
        val engine = RecordingEngine()
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)

        pump.pushFrameOffMain(engine, bitmap)

        assertTrue("pushFrame must be called", engine.pushed)
        assertFalse("inference must never run on Main", engine.onMainThread)
        assertEquals(4u, engine.width)
        assertEquals(2u, engine.height)
        assertEquals("RGB888 is three bytes per pixel", 4 * 2 * 3, engine.payloadSize)
    }

    @Test
    fun repeated_frames_do_not_reallocate_the_payload_buffer() {
        val buffers = PackedFrameBuffers()
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        val first = buffers.pack(bitmap)
        val second = buffers.pack(bitmap)

        assertSame("steady-state frames must reuse the same array", first, second)
        assertEquals("RGB888 is three bytes per pixel", 4 * 2 * 3, second.size)
    }

    @Test
    fun the_payload_buffer_grows_when_the_capture_size_changes() {
        val buffers = PackedFrameBuffers()
        val small = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        assertEquals(2 * 1 * 3, buffers.pack(small).size)
        val large = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        assertEquals(8 * 4 * 3, buffers.pack(large).size)
    }

    @Test
    fun inference_runs_on_a_dedicated_named_thread() = runTest {
        val pump = EngineFramePump(engineProvider = id.warung.monitor.di.EngineProvider(), cadence = InferenceCadence(), loadSource = CoolPhone, budget = InferenceBudget())
        val engine = RecordingEngine()
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)

        pump.pushFrameOffMain(engine, bitmap)

        assertTrue("pushFrame must be called", engine.pushed)
        assertEquals(
            "inference must not share the shared Default pool",
            EngineFramePump.INFERENCE_THREAD_NAME,
            engine.threadName,
        )
        assertEquals(4 * 2 * 3, engine.payloadSize)
    }

    @Test
    fun a_matching_bitmap_is_reused_by_the_capture_path() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val reused = VideoCaptures.reusableBuffer(bitmap, 640, 360)
        assertSame("a same-size buffer must be reused", bitmap, reused)
    }

    @Test
    fun a_size_change_asks_for_a_new_bitmap() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        assertEquals(null, VideoCaptures.reusableBuffer(bitmap, 320, 180))
        assertEquals(null, VideoCaptures.reusableBuffer(bitmap, 640, 359))
    }

    @Test
    fun a_recycled_bitmap_is_never_reused() {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        bitmap.recycle()
        assertEquals(
            "getBitmap would throw on a recycled bitmap",
            null,
            VideoCaptures.reusableBuffer(bitmap, 640, 360),
        )
    }

    @Test
    fun the_first_capture_has_no_buffer_to_reuse() {
        assertEquals(null, VideoCaptures.reusableBuffer(null, 640, 360))
    }
    /** Deterministic readings: two FPS, exactly like a cool phone with RAM. */
    private object CoolPhone : DeviceLoadSource {
        override fun thermalStatus(): Int? = android.os.PowerManager.THERMAL_STATUS_NONE

        override fun availableRamMb(): Long? = 1_000L
    }

    private class RecordingEngine : EngineInterface {
        var threadName = ""
        var pushed = false
        var onMainThread = false
        var width = 0u
        var height = 0u
        var payloadSize = 0

        override fun clearListeners() = Unit

        override fun health(): EngineHealth = throw UnsupportedOperationException("not used")

        override fun lastDetections(): List<id.warung.monitor.core.DetectionBox> = emptyList()

        override fun pushFrame(width: UInt, height: UInt, rgb: ByteArray): List<TableEvent> {
            pushed = true
            onMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            threadName = Thread.currentThread().name
            this.width = width
            this.height = height
            payloadSize = rgb.size
            return emptyList()
        }

        override fun reportStreamError(status: StreamStatus) = Unit

        override fun setTables(tablesJson: String) = Unit

        override fun tables(): List<id.warung.monitor.core.TableSnapshot> = emptyList()

        override fun start(config: id.warung.monitor.core.EngineConfig) = Unit

        override fun stop() = Unit

        override fun subscribe(listener: id.warung.monitor.core.EngineListener) = Unit
    }
}
