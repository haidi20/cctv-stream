package id.warung.monitor.feature.tables.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.warung.monitor.core.model.MappingFrame
import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.PolygonValidator
import id.warung.monitor.core.model.TableId
import id.warung.monitor.feature.tables.domain.MappingTransform
import id.warung.monitor.feature.tables.domain.ScreenPan
import id.warung.monitor.feature.tables.domain.ScreenPoint
import id.warung.monitor.feature.tables.domain.ScreenSize
import id.warung.monitor.feature.tables.domain.TableDraft
import java.util.Locale
import kotlin.math.roundToInt

private data class VertexHit(val tableId: TableId, val index: Int)

private sealed interface DragTarget {
    data class Vertex(val tableId: TableId, val index: Int) : DragTarget
    data object Pan : DragTarget
    data object Ignore : DragTarget
}

/**
 * Interactive table-map canvas.
 *
 * Zoom and pan are local UI state, reset on recomposition of the screen (and so
 * on rotation). Table geometry never leaves 0..1; conversion happens only when
 * a gesture ends or a vertex moves.
 */
@Composable
fun MappingCanvas(
    frame: MappingFrame,
    tables: List<TableDraft>,
    selectedTableId: TableId?,
    selectedVertex: Int?,
    drawingTableId: TableId?,
    modifier: Modifier = Modifier,
    backdrop: ImageBitmap? = null,
    onAddPoint: (NormalizedPoint) -> Unit,
    onSelectTable: (TableId?) -> Unit,
    onSelectVertex: (TableId, Int) -> Unit,
    onMoveVertex: (TableId, Int, NormalizedPoint) -> Unit,
    onRemoveVertex: (TableId, Int) -> Unit,
) {
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 24.dp.toPx() }
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()

    var viewport by remember { mutableStateOf(ScreenSize(0f, 0f)) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(ScreenPan.None) }
    var suppressVertexDragUntil by remember { mutableLongStateOf(0L) }

    val transform = remember(viewport, frame.width, frame.height) {
        if (viewport.width > 0f && viewport.height > 0f) {
            MappingTransform(viewport, frame.width, frame.height)
        } else {
            null
        }
    }

    val currentTransform by rememberUpdatedState(transform)
    val currentTables by rememberUpdatedState(tables)
    val currentZoom by rememberUpdatedState(zoom)
    val currentPan by rememberUpdatedState(pan)
    val currentDrawing by rememberUpdatedState(drawingTableId)

    fun vertexAt(position: Offset): VertexHit? {
        val active = currentTransform ?: return null
        var best: VertexHit? = null
        var bestDistance = hitRadiusPx
        currentTables.forEach { table ->
            table.points.forEachIndexed { index, point ->
                val screen = active.toScreen(point, currentZoom, currentPan)
                val distance = (Offset(screen.x, screen.y) - position).getDistance()
                if (distance <= bestDistance) {
                    bestDistance = distance
                    best = VertexHit(table.id, index)
                }
            }
        }
        return best
    }

    fun normalizedAt(position: Offset): NormalizedPoint? {
        val active = currentTransform ?: return null
        return active.toNormalized(
            ScreenPoint(position.x, position.y),
            currentZoom,
            currentPan,
        )
    }

    fun tableAt(position: Offset): TableId? {
        val normalized = normalizedAt(position) ?: return null
        return currentTables.lastOrNull { table ->
            table.points.size >= 3 && PolygonValidator.contains(table.points, normalized)
        }?.id
    }

    fun resolveDrag(position: Offset): DragTarget {
        val vertex = vertexAt(position)
        if (vertex != null) {
            if (SystemClock.uptimeMillis() < suppressVertexDragUntil) return DragTarget.Ignore
            return DragTarget.Vertex(vertex.tableId, vertex.index)
        }
        return if (currentDrawing == null) DragTarget.Pan else DragTarget.Ignore
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .background(Color.Black)
            .onSizeChanged { size ->
                viewport = ScreenSize(size.width.toFloat(), size.height.toFloat())
            }
            .testTag("mapping-canvas"),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val active = transform ?: return@Canvas
            val topLeft = active.toScreen(NormalizedPoint(0.0, 0.0), zoom, pan)
            val bottomRight = active.toScreen(NormalizedPoint(1.0, 1.0), zoom, pan)
            val dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt())
            val dstSize = IntSize(
                (bottomRight.x - topLeft.x).roundToInt().coerceAtLeast(1),
                (bottomRight.y - topLeft.y).roundToInt().coerceAtLeast(1),
            )

            if (backdrop != null) {
                drawImage(
                    image = backdrop,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(backdrop.width, backdrop.height),
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    filterQuality = FilterQuality.Low,
                )
            } else {
                drawDemoRoom(active, zoom, pan)
            }

            val labelStyle = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            val vertexRadius = 6.dp.toPx()
            val selectedRadius = 9.dp.toPx()
            val strokeWidth = 2.dp.toPx()

            tables.forEach { table ->
                val isSelected = table.id == selectedTableId
                val isDrawing = table.id == drawingTableId
                val stroke = when {
                    isDrawing -> colors.primary
                    isSelected -> colors.tertiary
                    else -> colors.secondary
                }
                val fill = stroke.copy(alpha = if (isSelected || isDrawing) 0.30f else 0.18f)
                val screens = table.points.map { active.toScreen(it, zoom, pan) }

                if (screens.size >= 2) {
                    val path = Path().apply {
                        moveTo(screens.first().x, screens.first().y)
                        screens.drop(1).forEach { lineTo(it.x, it.y) }
                        if (screens.size >= 3) close()
                    }
                    if (screens.size >= 3) drawPath(path, color = fill, style = Fill)
                    drawPath(
                        path = path,
                        color = stroke,
                        style = Stroke(width = strokeWidth, pathEffect = if (isDrawing) dash else null),
                    )
                }

                screens.forEachIndexed { index, screen ->
                    val center = Offset(screen.x, screen.y)
                    val highlighted = isSelected && index == selectedVertex
                    drawCircle(
                        color = Color.White,
                        radius = if (highlighted) selectedRadius else vertexRadius,
                        center = center,
                    )
                    drawCircle(
                        color = stroke,
                        radius = if (highlighted) selectedRadius else vertexRadius,
                        center = center,
                        style = Stroke(width = strokeWidth),
                    )
                }

                if (screens.size >= 3) {
                    val centroid = PolygonValidator.centroid(table.points)
                    val anchor = active.toScreen(centroid, zoom, pan)
                    val layout = textMeasurer.measure(
                        text = AnnotatedString(table.displayLabel),
                        style = labelStyle,
                    )
                    val width = layout.size.width.toFloat()
                    val height = layout.size.height.toFloat()
                    val paddingX = 6.dp.toPx()
                    val paddingY = 3.dp.toPx()
                    drawRoundRect(
                        color = colors.surface.copy(alpha = 0.82f),
                        topLeft = Offset(anchor.x - width / 2f - paddingX, anchor.y - height / 2f - paddingY),
                        size = Size(width + paddingX * 2f, height + paddingY * 2f),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(anchor.x - width / 2f, anchor.y - height / 2f),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(transform) {
                    detectTapGestures(
                        onTap = { position ->
                            val active = currentTransform ?: return@detectTapGestures
                            val vertex = vertexAt(position)
                            if (vertex != null) {
                                onSelectVertex(vertex.tableId, vertex.index)
                                return@detectTapGestures
                            }
                            val tableId = tableAt(position)
                            if (tableId != null) {
                                onSelectTable(tableId)
                                if (currentDrawing == tableId) {
                                    normalizedAt(position)?.let(onAddPoint)
                                }
                            } else if (currentDrawing != null) {
                                normalizedAt(position)?.let(onAddPoint)
                            } else {
                                onSelectTable(null)
                            }
                        },
                        onLongPress = { position ->
                            val vertex = vertexAt(position) ?: return@detectTapGestures
                            suppressVertexDragUntil = SystemClock.uptimeMillis() + 400L
                            onRemoveVertex(vertex.tableId, vertex.index)
                        },
                    )
                }
                .pointerInput(transform) {
                    var gesturePan = pan
                    var gestureZoom = zoom
                    detectDragAndTransform(
                        resolveDrag = ::resolveDrag,
                        onDrag = { target, position, delta ->
                            val active = currentTransform ?: return@detectDragAndTransform
                            when (target) {
                                is DragTarget.Vertex -> {
                                    val point = active.toNormalized(
                                        ScreenPoint(position.x, position.y),
                                        gestureZoom,
                                        gesturePan,
                                    )
                                    onMoveVertex(target.tableId, target.index, point)
                                }
                                DragTarget.Pan -> {
                                    gesturePan = active.clampPan(
                                        ScreenPan(gesturePan.x + delta.x, gesturePan.y + delta.y),
                                        gestureZoom,
                                    )
                                    pan = gesturePan
                                }
                                DragTarget.Ignore -> Unit
                            }
                        },
                        onTransform = { centroid, panChange, zoomChange ->
                            val active = currentTransform ?: return@detectDragAndTransform
                            val newZoom = (gestureZoom * zoomChange).coerceIn(
                                MappingTransform.MIN_ZOOM,
                                MappingTransform.MAX_ZOOM,
                            )
                            val anchored = active.zoomAnchored(
                                ScreenPoint(centroid.x, centroid.y),
                                gestureZoom,
                                newZoom,
                                gesturePan,
                            )
                            gesturePan = active.clampPan(
                                ScreenPan(anchored.x + panChange.x, anchored.y + panChange.y),
                                newZoom,
                            )
                            gestureZoom = newZoom
                            pan = gesturePan
                            zoom = gestureZoom
                        },
                    )
                },
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color.Black.copy(alpha = 0.55f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = String.format(Locale.US, "%.1fx", zoom),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                TextButton(
                    onClick = {
                        zoom = 1f
                        pan = ScreenPan.None
                    },
                ) {
                    Text("Fit", color = Color.White)
                }
                TextButton(
                    onClick = {
                        val targetZoom = 2f
                        transform?.let { active ->
                            zoom = targetZoom
                            pan = active.centeredPan(targetZoom)
                        }
                    },
                ) {
                    Text("2x", color = Color.White)
                }
            }
        }

        if (backdrop == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.45f),
            ) {
                Text(
                    text = "Bingkai demo",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        if (drawingTableId != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
                shape = RoundedCornerShape(10.dp),
                color = colors.primary.copy(alpha = 0.9f),
            ) {
                Text(
                    text = "Ketuk bingkai untuk menambah titik",
                    color = colors.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * One-finger drag (vertex or pan) plus two-finger transform.
 *
 * Kept separate from [detectTapGestures] so a tap never becomes a drag and so a
 * pinch cancels the tap detector by consuming movement.
 */
private suspend fun PointerInputScope.detectDragAndTransform(
    resolveDrag: (Offset) -> DragTarget,
    onDrag: (DragTarget, Offset, Offset) -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var pastSlop = false
        var isTransform = false
        var target: DragTarget = DragTarget.Ignore

        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break

            if (event.changes.count { it.pressed } >= 2) isTransform = true

            if (isTransform) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    onTransform(event.calculateCentroid(useCurrent = true), pan, zoom)
                    event.changes.forEach { it.consume() }
                }
                continue
            }

            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
            if (!pastSlop) {
                val moved = (change.position - down.position).getDistance()
                if (moved > viewConfiguration.touchSlop) {
                    pastSlop = true
                    target = resolveDrag(down.position)
                }
            }
            if (pastSlop) {
                val delta = change.position - change.previousPosition
                if (delta != Offset.Zero) {
                    onDrag(target, change.position, delta)
                    change.consume()
                }
            }
        }
    }
}