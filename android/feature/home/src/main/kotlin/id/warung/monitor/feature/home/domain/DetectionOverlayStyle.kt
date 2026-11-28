package id.warung.monitor.feature.home.domain

import androidx.compose.ui.geometry.Offset
import id.warung.monitor.core.model.NormalizedPoint

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
     * Oldest box still worth drawing, in milliseconds.
     *
     * A box older than this describes where a person *was*, and a walking
     * person covers roughly a table width in that time. Hiding the overlay for
     * a moment is honest; drawing the box in the wrong place is not
     * (`docs/detection-quality-todo.md` Q-02).
     */
    const val StaleAfterMillis: Long = 1_200L

    /**
     * Whether [ageMillis] is too old to draw.
     *
     * An unknown age is not "fresh": the caller has no evidence the box is
     * current, so it is treated as stale. A negative age (a clock that moved
     * backwards) is clamped to fresh rather than dropping every box.
     */
    fun isStale(ageMillis: Long?): Boolean =
        ageMillis == null || ageMillis > StaleAfterMillis

    /**
     * [boxes] that are still young enough to draw.
     *
     * Applied to the whole list at once because every box in one payload comes
     * from the same analysed frame: a mixed list would be a bug in the caller,
     * not something the overlay should paper over.
     */
    fun freshBoxes(boxes: List<DetectionRect>): List<DetectionRect> =
        if (boxes.isEmpty() || isStale(boxes.first().ageMillis)) emptyList() else boxes

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

    /** Stroke width of a mapped table outline, in density-independent pixels. */
    const val ScopeStrokeDp: Float = 1.5f

    /** ARGB of the manual mapping outline (blue, never the green person box). */
    const val ScopeColorArgb: Long = 0xFF3B82F6

    /** Opacity of the table outline itself. */
    const val ScopeAlpha: Float = 0.9f

    /**
     * Opacity of the faint wash inside a footprint.
     *
     * A bare outline is hard to judge against a busy video: the user has to
     * decide whether the table really sits inside it. A very light fill makes
     * the area readable without hiding the people the green boxes describe.
     */
    const val ScopeFillAlpha: Float = 0.12f

    /**
     * Most footprints drawn at once.
     *
     * A map is user-drawn and normally a handful of tables. This is a guard,
     * not a product limit: a corrupt map with hundreds of polygons would
     * otherwise rebuild hundreds of paths on every detection frame (4 Hz).
     */
    const val MaxScopePolygons: Int = 64

    /**
     * Pixel stroke width for the scope outline at [density].
     *
     * Slightly thinner than the green person box so the mapping boundary reads
     * as the backdrop and the detection stays the foreground.
     */
    fun scopeStrokeWidthPx(density: Float): Float =
        ScopeStrokeDp * density.coerceAtLeast(0f)

    /**
     * Whether [points] form a shape the canvas can outline.
     *
     * Two points are a line and one point is a dot; drawing either as a
     * "table" would mislead the user about where the footprint is. The
     * validator already rejects these, so this is the drawing-side guard.
     */
    fun isDrawableScope(points: List<NormalizedPoint>): Boolean = points.size >= 3

    /**
     * [polygons] that are worth drawing, capped at [MaxScopePolygons].
     *
     * Empty and degenerate footprints are dropped rather than drawn as
     * nothing, so the overlay can skip its canvas entirely when the list comes
     * back empty.
     */
    fun drawableScopes(polygons: List<List<NormalizedPoint>>): List<List<NormalizedPoint>> {
        if (polygons.isEmpty()) return emptyList()
        return polygons.filter(::isDrawableScope).take(MaxScopePolygons)
    }

    /**
     * [points] projected into a `width` x `height` canvas, or `null` when the
     * footprint cannot be drawn.
     *
     * Kept out of the composable so the projection is checked on the JVM: a
     * Canvas test cannot read vertices back from the node tree. A non-positive
     * or non-finite canvas is treated as "not laid out yet" instead of
     * producing `NaN` offsets that make `Path` throw at draw time.
     */
    fun scopePathPoints(
        points: List<NormalizedPoint>,
        width: Float,
        height: Float,
    ): List<Offset>? {
        if (!isDrawableScope(points)) return null
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
        return points.map { point ->
            val x = (point.x.toFloat() * width).coerceIn(0f, width)
            val y = (point.y.toFloat() * height).coerceIn(0f, height)
            Offset(x, y)
        }
    }
}
