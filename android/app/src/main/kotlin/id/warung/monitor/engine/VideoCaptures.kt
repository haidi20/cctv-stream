package id.warung.monitor.engine

import android.graphics.Bitmap
import android.view.TextureView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads frames from the composited video texture.
 *
 * A [TextureView] is used instead of `PixelCopy` on a `SurfaceView` because the
 * activity sets `FLAG_SECURE`, which can make copying the window surface fail.
 * Reading the app's own texture in-process is not a screen capture and works
 * for the RTSP preview. Media3 owns the decoding; this only samples pixels.
 */
object VideoCaptures {

    /**
     * Captures the current texture, optionally downscaled to [maxWidth].
     * Returns `null` when the view is not ready; callers skip that tick.
     */
    suspend fun capture(view: TextureView, maxWidth: Int? = null): Bitmap? =
        withContext(Dispatchers.Main) {
            if (!view.isAvailable) return@withContext null
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) return@withContext null
            val targetWidth = maxWidth?.coerceAtMost(width)?.coerceAtLeast(1) ?: width
            val targetHeight = (height.toLong() * targetWidth / width).toInt().coerceAtLeast(1)
            runCatching { view.getBitmap(targetWidth, targetHeight) }.getOrNull()
        }
}