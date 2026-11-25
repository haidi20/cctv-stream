package id.warung.monitor.engine

import android.view.TextureView
import id.warung.monitor.di.EngineProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 */
@Singleton
class EngineFramePump @Inject constructor(
    private val engineProvider: EngineProvider,
) {

    suspend fun run(cameraId: String, textureProvider: () -> TextureView?) {
        while (currentCoroutineContext().isActive) {
            delay(INTERVAL_MS)
            if (!currentCoroutineContext().isActive) return
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
                VideoCaptures.capture(texture, MAX_FRAME_WIDTH)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            } ?: continue
            try {
                val rgb = withContext(Dispatchers.Default) { Rgb888.fromBitmap(bitmap) }
                engine.pushFrame(
                    bitmap.width.toUInt(),
                    bitmap.height.toUInt(),
                    rgb,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // A stop may race a tick; the next session starts cleanly.
            } finally {
                bitmap.recycle()
            }
        }
    }

    companion object {
        const val INTERVAL_MS: Long = 500L
        const val MAX_FRAME_WIDTH: Int = 640
    }
}