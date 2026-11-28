package id.warung.monitor.feature.home.domain

/**
 * Drawing rules for the person overlay.
 *
 * These live outside the composable so the geometry and the visual weight can be
 * checked on the JVM: a `Canvas` test cannot read back a stroke width or an
 * alpha value from the node tree, and a hard-coded `2f` stroke is a raw pixel
 * count that thins out on a dense screen and looks uneven next to the table
 * polygons (`docs/detection-quality-todo.md` Q-11).
 */
object DetectionOverlayStyle {
    /** Stroke width in density-independent pixels, converted with the live density. */
    const val StrokeDp: Float = 2f

    /** Opacity of a box the detector barely trusts. */
    const val MinAlpha: Float = 0.35f

    /** Opacity added on top of [MinAlpha] as confidence reaches 1.0. */
    const val AlphaSpan: Float = 0.65f

    /**
     * Stroke width in pixels for [density].
     *
     * Compose's `Stroke` takes pixels, so passing `2f` directly means "2 physical
     * pixels": nearly invisible on a 3x screen. Multiplying by the density keeps
     * the line the same physical thickness everywhere.
     */
    fun strokeWidthPx(density: Float): Float = StrokeDp * density.coerceAtLeast(0f)

    /**
     * Box opacity for a detector [confidence].
     *
     * A 0.36 box and a 0.92 box used to look identical, so the user could not
     * tell a guess from a solid hit. The floor keeps even a weak box visible;
     * values outside `0..1` are clamped rather than trusted.
     */
    fun alphaFor(confidence: Float): Float {
        if (confidence.isNaN()) return MinAlpha
        return MinAlpha + AlphaSpan * confidence.coerceIn(0f, 1f)
    }

    /**
     * [rect] trimmed to the visible `0..1` frame, or `null` when nothing is left.
     *
     * Boxes are clamped to the frame by Rust, but a stroke is drawn centred on
     * the path, so half of it still paints outside the video. Clipping here (and
     * again with `clipRect` in the canvas) keeps green pixels off the card
     * border. A box that only touches the edge has no area to draw and returns
     * `null` instead of a zero-size rectangle.
     */
    fun clippedToFrame(rect: DetectionRect): DetectionRect? {
        if (!rect.left.isFinite() ||
            !rect.top.isFinite() ||
            !rect.width.isFinite() ||
            !rect.height.isFinite()
        ) {
            return null
        }
        val left = rect.left.coerceIn(0f, 1f)
        val top = rect.top.coerceIn(0f, 1f)
        val right = (rect.left + rect.width).coerceIn(0f, 1f)
        val bottom = (rect.top + rect.height).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return rect.copy(left = left, top = top, width = right - left, height = bottom - top)
    }
}
