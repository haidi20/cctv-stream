package id.warung.monitor.feature.home.domain

/**
 * Aspect ratio of the frame the preview is actually decoding.
 *
 * Detection boxes arrive normalised to `0..1` of the *frame*, so the container
 * they are drawn into must have the same shape as the frame. Drawing them into
 * a fixed 16:9 card while Media3 letterboxes a 4:3 stream inside it shifts every
 * box by the width of the black bars (see `docs/detection-quality-todo.md`
 * Q-01). This type is the one place that turns a reported pixel size into that
 * shape, and it refuses nonsense instead of producing an infinite ratio.
 */
object VideoAspect {
    /** Shape used before Media3 reports a size; no boxes are drawn meanwhile. */
    const val Fallback: Float = 16f / 9f

    /**
     * Ratio `width / height`, or `null` when either side is not positive.
     *
     * Media3 reports `0x0` before the first frame is decoded; a zero height would
     * otherwise become `Infinity` and make `aspectRatio()` throw.
     */
    fun of(width: Int, height: Int): Float? =
        if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null
}
