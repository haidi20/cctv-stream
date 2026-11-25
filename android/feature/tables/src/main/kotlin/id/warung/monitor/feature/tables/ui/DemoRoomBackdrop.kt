package id.warung.monitor.feature.tables.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.feature.tables.domain.MappingTransform
import id.warung.monitor.feature.tables.domain.ScreenPan

/**
 * Stand-in for a camera frame while no RTSP/CCTV source is available.
 *
 * Everything is drawn through [MappingTransform], so the decorative room zooms
 * and pans exactly like a real frame will. It is deliberately low-contrast so
 * the user's polygons stay readable on top.
 */
internal fun DrawScope.drawDemoRoom(
    transform: MappingTransform,
    zoom: Float,
    pan: ScreenPan,
) {
    fun point(x: Double, y: Double): Offset {
        val screen = transform.toScreen(NormalizedPoint(x, y), zoom, pan)
        return Offset(screen.x, screen.y)
    }

    fun rect(left: Double, top: Double, right: Double, bottom: Double): Pair<Offset, Size> {
        val topLeft = point(left, top)
        val bottomRight = point(right, bottom)
        return topLeft to Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y)
    }

    fun furniture(left: Double, top: Double, right: Double, bottom: Double) {
        val (tableOrigin, tableSize) = rect(left, top, right, bottom)
        drawRoundRect(
            color = TableWood.copy(alpha = 0.55f),
            topLeft = tableOrigin,
            size = tableSize,
            cornerRadius = CornerRadius(tableSize.minDimension * 0.08f),
        )
        drawRoundRect(
            color = TableEdge.copy(alpha = 0.7f),
            topLeft = tableOrigin,
            size = tableSize,
            cornerRadius = CornerRadius(tableSize.minDimension * 0.08f),
            style = Stroke(width = 2f),
        )

        val centerX = (left + right) / 2.0
        val centerY = (top + bottom) / 2.0
        val radius = minOf(right - left, bottom - top) * 0.12
        val chairs = listOf(
            centerX to top - radius * 1.6,
            centerX to bottom + radius * 1.6,
            left - radius * 1.6 to centerY,
            right + radius * 1.6 to centerY,
        )
        chairs.forEach { (x, y) ->
            val seat = point(x.coerceIn(0.01, 0.99), y.coerceIn(0.2, 0.99))
            drawCircle(color = Chair.copy(alpha = 0.6f), radius = 9f, center = seat)
        }
    }

    val (origin, full) = rect(0.0, 0.0, 1.0, 1.0)
    drawRect(color = FloorDark, topLeft = origin, size = full)

    val (wallOrigin, wallSize) = rect(0.0, 0.0, 1.0, 0.18)
    drawRect(color = WallDark, topLeft = wallOrigin, size = wallSize)

    val gridColor = Color.White.copy(alpha = 0.05f)
    for (step in 1 until 10) {
        val fraction = step / 10.0
        val (verticalTop, _) = rect(fraction, 0.18, fraction, 0.18)
        val (verticalBottom, _) = rect(fraction, 1.0, fraction, 1.0)
        drawLine(gridColor, verticalTop, verticalBottom, strokeWidth = 1f)
        val (horizontalLeft, _) = rect(0.0, fraction, 0.0, fraction)
        val (horizontalRight, _) = rect(1.0, fraction, 1.0, fraction)
        drawLine(gridColor, horizontalLeft, horizontalRight, strokeWidth = 1f)
    }

    furniture(0.08, 0.52, 0.26, 0.70)
    furniture(0.36, 0.24, 0.54, 0.42)
    furniture(0.68, 0.50, 0.86, 0.68)
    furniture(0.42, 0.72, 0.60, 0.88)

    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
            startY = origin.y,
            endY = origin.y + full.height,
        ),
        topLeft = origin,
        size = full,
    )
}

private val FloorDark = Color(0xFF23262A)
private val WallDark = Color(0xFF16181B)
private val TableWood = Color(0xFF8A6A4A)
private val TableEdge = Color(0xFFB08A62)
private val Chair = Color(0xFF4A4F55)