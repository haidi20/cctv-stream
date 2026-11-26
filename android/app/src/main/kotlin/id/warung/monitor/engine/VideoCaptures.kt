package id.warung.monitor.engine

import android.graphics.Bitmap
import android.view.TextureView
import androidx.core.graphics.createBitmap
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
     *
     * One-shot convenience for the mapping editor, which needs a stable bitmap
     * the user can pinch and pan. Recurring capture must use [captureInto]
     * instead so a monitoring session does not allocate a bitmap every tick.
     */
    suspend fun capture(view: TextureView, maxWidth: Int? = null): Bitmap? =
        withContext(Dispatchers.Main) {
            val target = targetSize(view, maxWidth) ?: return@withContext null
            val bitmap = try {
                createBitmap(target.first, target.second)
            } catch (_: Throwable) {
                return@withContext null
            }
            if (copyTexture(view, bitmap)) bitmap else null
        }

    /**
     * Captures into [reuse] when its size and config already match the target.
     *
     * The caller owns the returned bitmap and must not recycle it while it may
     * be reused; a size change simply returns a fresh bitmap and leaves the old
     * one for the caller to recycle. This keeps a 2 FPS monitoring session from
     * allocating (and later collecting) roughly a megabyte every 500 ms, which
     * on a low-end phone shows up as GC pauses in whatever app the user opened.
     */
    suspend fun captureInto(
        view: TextureView,
        reuse: Bitmap?,
        maxWidth: Int? = null,
    ): Bitmap? = withContext(Dispatchers.Main) {
        val target = targetSize(view, maxWidth) ?: return@withContext null
        val (width, height) = target
        val buffer = reusableBuffer(reuse, width, height)
            ?: try {
                createBitmap(width, height)
            } catch (_: Throwable) {
                return@withContext null
            }
        if (copyTexture(view, buffer)) buffer else null
    }

    /**
     * Copies the current texture into [target].
     *
     * `TextureView.getBitmap(Bitmap)` returns `Bitmap?` (the same instance on
     * success, `null` when the surface cannot be copied). The platform return
     * type degrades to `Any` inside `runCatching`, so the check is explicit.
     */
    @Suppress("ReturnCount")
    private fun copyTexture(view: TextureView, target: Bitmap): Boolean {
        val copied: Bitmap? = try {
            view.getBitmap(target)
        } catch (_: Throwable) {
            null
        }
        return copied != null
    }

    /**
     * [reuse] when it is exactly the size the texture copy needs, else `null`.
     *
     * Pulled out as a pure function so the reuse decision is testable without a
     * real texture. A recycled bitmap is never returned: `getBitmap` would
     * throw on it.
     */
    internal fun reusableBuffer(reuse: Bitmap?, width: Int, height: Int): Bitmap? =
        reuse?.takeIf { !it.isRecycled && it.width == width && it.height == height }

    /** Target capture size in pixels, or `null` when the view is not ready. */
    private fun targetSize(view: TextureView, maxWidth: Int?): Pair<Int, Int>? {
        if (!view.isAvailable) return null
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null
        val targetWidth = maxWidth?.coerceAtMost(width)?.coerceAtLeast(1) ?: width
        val targetHeight = (height.toLong() * targetWidth / width).toInt().coerceAtLeast(1)
        return targetWidth to targetHeight
    }
}
