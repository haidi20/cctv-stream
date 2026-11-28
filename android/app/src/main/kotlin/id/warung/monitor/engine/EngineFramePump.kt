package id.warung.monitor.engine

import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.view.TextureView
import id.warung.monitor.di.EngineProvider
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Feeds the Rust engine at 2 FPS while a preview texture is visible.
 *
 * The engine remains the owner of inference, polygons, and debounce. A missing
 * model, a stopped session, or a detached texture simply skips the tick; no
 * exception is logged with frame data or credentials.
 *
 * Inference is CPU-bound and can take tens of milliseconds per frame. On a
 * low-end phone that must not compete with the app the user switches to, so
 * every FFI call (`health()` and `pushFrame`) runs on one dedicated worker
 * thread at [Process.THREAD_PRIORITY_BACKGROUND] instead of the shared
 * `Dispatchers.Default` pool. The thread is owned by this process-lifetime
 * singleton, is a daemon, and is never shut down: at most one thread exists no
 * matter how often the preview is opened.
 *
 * Only the texture read stays on the caller's thread (Main, via
 * `LaunchedEffect`): [TextureView]-derived state is main-thread owned. Reading
 * `health()` there instead would put a lock wait straight on the UI thread -
 * Rust takes the same state lock while it fingerprints a frame - so the two
 * engine calls are hop boundaries.
 */
@Singleton
class EngineFramePump @Inject constructor(
    private val engineProvider: EngineProvider,
    private val cadence: InferenceCadence,
    private val loadSource: DeviceLoadSource,
    private val budget: InferenceBudget,
) {

    private var inferenceDispatcher: CoroutineDispatcher = dedicatedDispatcher()

    /**
     * Swaps the inference dispatcher for a deterministic one.
     *
     * Production never calls this: the dedicated background thread is the
     * contract. Pacing tests install a virtual-time dispatcher so their tick
     * counts stay deterministic; the real thread (and the hop off Main) is
     * covered by `the_health_snapshot_is_read_off_the_main_thread` and
     * `inference_runs_on_a_dedicated_named_thread`, which use the default.
     */
    internal fun useDispatcherForTest(dispatcher: CoroutineDispatcher): EngineFramePump = apply {
        inferenceDispatcher = dispatcher
    }

    private val buffers = PackedFrameBuffers()

    /**
     * @param keepRunning polled every tick; returning `false` ends the loop.
     *   The preview passes `PreviewState.Playing`, so a frozen last frame is
     *   never mistaken for a live camera by the engine.
     */
    suspend fun run(
        cameraId: String,
        textureProvider: () -> TextureView?,
        keepRunning: () -> Boolean = { true },
    ) {
        // Owned by this loop and only touched on Main, so one bitmap serves the
        // whole session instead of a fresh ~0.9 MB allocation every tick.
        var buffer: Bitmap? = null
        while (currentCoroutineContext().isActive) {
            val started = SystemClock.elapsedRealtime()
            if (!keepRunning()) return
            // Every path inside `tick` returns promptly; the wait is applied
            // once at the loop boundary so a failed capture can never turn
            // this into a busy spin.
            buffer = tick(cameraId, textureProvider, buffer)
            val work = SystemClock.elapsedRealtime() - started
            delay(cadence.paceDelayMillis(tickDelayMillis(), work))
        }
    }

    /**
     * One capture/inference pass. Returns the bitmap the next tick may reuse.
     *
     * A missing engine, a stale session, an unavailable texture, or a failed
     * copy simply ends this pass; the caller still waits for the interval.
     */
    private suspend fun tick(
        cameraId: String,
        textureProvider: () -> TextureView?,
        buffer: Bitmap?,
    ): Bitmap? {
        val engine = engineOffMain() ?: return buffer
        val health = healthOffMain(engine) ?: return buffer
        if (health.cameraId != cameraId || !health.modelReady) return buffer
        val texture = textureProvider() ?: return buffer
        val bitmap = try {
            VideoCaptures.captureInto(texture, buffer, MAX_FRAME_WIDTH)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        } ?: return buffer
        // `captureInto` returns the same instance while the texture size is
        // stable, so steady-state ticks allocate nothing. A size change
        // returns a new bitmap and lets the previous one be collected.
        try {
            pushFrameOffMain(engine, bitmap)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // A stop may race a tick; the next session starts cleanly.
        }
        return bitmap
    }

    /**
     * Engine handle read on the inference thread.
     *
     * `EngineProvider.engineOrNull()` dlopens `libcore_ffi.so` through JNA on
     * first use, which is disk I/O and must not land on Main. Returns `null`
     * when the native library is missing, exactly like the provider.
     */
    internal suspend fun engineOffMain(): id.warung.monitor.core.EngineInterface? =
        withContext(inferenceDispatcher) { engineProvider.engineOrNull() }

    /**
     * One health snapshot read on the inference thread.
     *
     * `null` means "no reading now": a `stop` racing a tick throws a normal FFI
     * error and the next session starts cleanly. [CancellationException] is
     * never swallowed, so cancelling the preview really ends the loop.
     */
    internal suspend fun healthOffMain(
        engine: id.warung.monitor.core.EngineInterface,
    ): id.warung.monitor.core.EngineHealth? = withContext(inferenceDispatcher) {
        try {
            engine.health()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Packs and hands one frame to Rust on the dedicated inference thread.
     *
     * `run` is called from `LaunchedEffect`, which runs on Main. The packing
     * and the synchronous FFI call both happen off Main, on a background
     * priority thread, and the IntArray/ByteArray are reused between frames so
     * a 640-wide frame no longer dirties the heap every tick. UniFFI copies
     * `rgb` into Rust, so the shared array is not touched again after this
     * block; an in-flight call cannot be interrupted, it simply finishes and
     * the caller observes cancellation on the next suspension.
     */
    internal suspend fun pushFrameOffMain(
        engine: id.warung.monitor.core.EngineInterface,
        bitmap: Bitmap,
    ) {
        withContext(inferenceDispatcher) {
            val rgb = buffers.pack(bitmap)
            engine.pushFrame(
                bitmap.width.toUInt(),
                bitmap.height.toUInt(),
                rgb,
            )
        }
    }

    private fun tickDelayMillis(): Long = cadence.delayMillis(
        thermalStatus = runCatching { loadSource.thermalStatus() }.getOrNull(),
        availableRamMb = runCatching { loadSource.availableRamMb() }.getOrNull(),
        requestedFps = budget.requestedFps(),
    )

    companion object {
        const val INTERVAL_MS: Long = InferenceCadence.NORMAL_DELAY_MS
        const val MAX_FRAME_WIDTH: Int = 640
        internal const val INFERENCE_THREAD_NAME: String = "cctv-inference"

        /**
         * One daemon thread at background priority for every FFI call.
         *
         * Process-lifetime and never shut down: this singleton outlives every
         * preview, so creating the thread per session would leak it. A shared
         * `Dispatchers.Default` worker would instead compete with the app the
         * user opens next to monitoring.
         */
        private fun dedicatedDispatcher(): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { runnable ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                }, INFERENCE_THREAD_NAME).apply { isDaemon = true }
            }.asCoroutineDispatcher()
    }
}

/**
 * RGB888 packing buffers reused across frames.
 *
 * Owned by [EngineFramePump] and touched only from its single inference thread,
 * so no locking is needed. Buffers are reallocated only when the captured
 * frame size changes, which on a fixed stream happens at most once.
 */
internal class PackedFrameBuffers {

    private var pixels = IntArray(0)
    private var rgb = ByteArray(0)

    /**
     * Returns the shared payload buffer for [bitmap].
     *
     * The array is only valid until the next call with a different frame, which
     * is safe here because the caller hands it straight to UniFFI and UniFFI
     * copies it into Rust before returning.
     */
    fun pack(bitmap: Bitmap): ByteArray {
        val needed = bitmap.width * bitmap.height
        if (pixels.size != needed) {
            pixels = IntArray(needed)
            rgb = ByteArray(needed * 3)
        }
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        Rgb888.packInto(pixels, rgb)
        return rgb
    }
}