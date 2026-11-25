package id.warung.monitor.feature.tables.domain

import id.warung.monitor.core.model.NormalizedPoint
import kotlin.math.min

/**
 * Pure screen <-> frame transform for the polygon editor.
 *
 * The formulas are the ones in `docs/tdd-plan.md` 3.2.2: `base` is the
 * ContentScale.Fit factor, [content] is the letterbox offset, and [zoom] is a
 * factor above fit. Pan is stored in viewport pixels so the same gesture means
 * the same thing at every zoom level.
 */
class MappingTransform(
    val viewport: ScreenSize,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    init {
        require(viewport.width > 0f && viewport.height > 0f) { "Viewport must be positive" }
        require(frameWidth > 0 && frameHeight > 0) { "Frame must be positive" }
    }

    val base: Float = min(
        viewport.width / frameWidth.toFloat(),
        viewport.height / frameHeight.toFloat(),
    )

    val content: ScreenPoint = ScreenPoint(
        x = (viewport.width - frameWidth * base) / 2f,
        y = (viewport.height - frameHeight * base) / 2f,
    )

    /** Offset applied before pan; recentres an axis whose scaled image is still narrow. */
    fun offsetFor(zoom: Float): ScreenPoint {
        val scaledWidth = scaledWidth(zoom)
        val scaledHeight = scaledHeight(zoom)
        return ScreenPoint(
            x = if (scaledWidth <= viewport.width + EPSILON) {
                (viewport.width - scaledWidth) / 2f
            } else {
                content.x
            },
            y = if (scaledHeight <= viewport.height + EPSILON) {
                (viewport.height - scaledHeight) / 2f
            } else {
                content.y
            },
        )
    }

    fun scaledWidth(zoom: Float): Float = zoom * base * frameWidth

    fun scaledHeight(zoom: Float): Float = zoom * base * frameHeight

    /** Project a normalised frame point to viewport pixels. */
    fun toScreen(point: NormalizedPoint, zoom: Float, pan: ScreenPan): ScreenPoint {
        val offset = offsetFor(zoom)
        return ScreenPoint(
            x = offset.x + pan.x + scaledWidth(zoom) * point.x.toFloat(),
            y = offset.y + pan.y + scaledHeight(zoom) * point.y.toFloat(),
        )
    }

    /** Viewport pixels to a normalised point; anything outside the frame is clamped. */
    fun toNormalized(screen: ScreenPoint, zoom: Float, pan: ScreenPan): NormalizedPoint {
        val offset = offsetFor(zoom)
        val width = scaledWidth(zoom)
        val height = scaledHeight(zoom)
        if (width <= 0f || height <= 0f) return NormalizedPoint(0.5, 0.5)
        val x = ((screen.x - offset.x - pan.x) / width).coerceIn(0f, 1f)
        val y = ((screen.y - offset.y - pan.y) / height).coerceIn(0f, 1f)
        return NormalizedPoint(x.toDouble(), y.toDouble())
    }

    /**
     * Keeps the image covering the viewport when zoomed in.
     *
     * When the scaled image is narrower than the viewport on an axis it is
     * centred, so pan on that axis is always zero.
     */
    fun clampPan(pan: ScreenPan, zoom: Float): ScreenPan {
        val scaledWidth = scaledWidth(zoom)
        val scaledHeight = scaledHeight(zoom)
        return ScreenPan(
            x = clampAxis(pan.x, viewport.width, content.x, scaledWidth),
            y = clampAxis(pan.y, viewport.height, content.y, scaledHeight),
        )
    }

    /** Pan that puts the image centre under the viewport centre. */
    fun centeredPan(zoom: Float): ScreenPan {
        val offset = offsetFor(zoom)
        return clampPan(
            ScreenPan(
                x = viewport.width / 2f - offset.x - scaledWidth(zoom) / 2f,
                y = viewport.height / 2f - offset.y - scaledHeight(zoom) / 2f,
            ),
            zoom,
        )
    }

    /**
     * Pan after zooming around [anchor] so the frame point under the finger stays put.
     *
     * [oldPan] must already be clamped. The result is clamped as well.
     */
    fun zoomAnchored(
        anchor: ScreenPoint,
        oldZoom: Float,
        newZoom: Float,
        oldPan: ScreenPan,
    ): ScreenPan {
        val targetZoom = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (targetZoom == oldZoom) return clampPan(oldPan, targetZoom)
        val normalised = toNormalized(anchor, oldZoom, oldPan)
        val offset = offsetFor(targetZoom)
        val candidate = ScreenPan(
            x = anchor.x - offset.x - scaledWidth(targetZoom) * normalised.x.toFloat(),
            y = anchor.y - offset.y - scaledHeight(targetZoom) * normalised.y.toFloat(),
        )
        return clampPan(candidate, targetZoom)
    }

    private fun clampAxis(value: Float, viewportSize: Float, contentStart: Float, scaled: Float): Float {
        if (scaled <= viewportSize + EPSILON) return 0f
        val lower = viewportSize - contentStart - scaled
        val upper = -contentStart
        return value.coerceIn(lower, upper)
    }

    companion object {
        const val MIN_ZOOM: Float = 1f
        const val MAX_ZOOM: Float = 8f
        private const val EPSILON: Float = 1e-3f
    }
}