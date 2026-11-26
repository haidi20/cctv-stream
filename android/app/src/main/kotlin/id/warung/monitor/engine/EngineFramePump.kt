package id.warung.monitor.engine

import android.graphics.Bitmap
import android.os.Process
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
 * every FFI call runs on one dedicated worker thread at
 * [Process.THREAD_PRIORITY_BACKGROUND] instead of the shared
 * `Dispatchers.Default` pool. The thread is owned by this process-lifetime
 * singleton, is a daemon, and is never shut down: at most one thread exists no
 * matter how often the preview is opened.
 */
@Singleton
class EngineFramePump @Inject constructor(
    private val engineProvider: EngineProvider,
) {

    private val inferenceDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, INFERENCE_THREAD_NAME).apply { isDaemon = true }
        }.asCoroutineDispatcher()

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
            delay(INTERVAL_MS)
            if (!currentCoroutineContext().isActive) return
            if (!keepRunning()) return
            val engine = engineProvider.engineOrNull() ?: continue
            val health = try {
                engine.health()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                continue
            }
            if (health.cameraId != cameraId || !health.modelReady) continue
            val texture = textureProvider() ?: continue
            val bitmap = try {
                VideoCaptures.captureInto(texture, buffer, MAX_FRAME_WIDTH)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            } ?: continue
            // `captureInto` returns the same instance while the texture size is
            // stable, so steady-state ticks allocate nothing. A size change
            // returns a new bitmap and lets the previous one be collected.
            buffer = bitmap
            try {
                pushFrameOffMain(engine, bitmap)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // A stop may race a tick; the next session starts cleanly.
            }
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

    companion object {
        const val INTERVAL_MS: Long = 500L
        const val MAX_FRAME_WIDTH: Int = 640
        internal const val INFERENCE_THREAD_NAME: String = "cctv-inference"
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