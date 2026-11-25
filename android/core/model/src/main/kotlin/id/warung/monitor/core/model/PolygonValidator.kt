package id.warung.monitor.core.model

/** Why a polygon draft cannot be used as a table footprint. */
enum class PolygonProblem {
    TOO_FEW_POINTS,
    COORDINATE_OUT_OF_RANGE,
    DEGENERATE_AREA,
    SELF_INTERSECTING,
}

/** Validation result for a polygon. */
sealed interface PolygonValidation {
    data class Valid(val polygon: TablePolygon) : PolygonValidation
    data class Invalid(val problem: PolygonProblem, val detail: String) : PolygonValidation
}

/**
 * Port of `rust-core/spatial-engine/src/polygon.rs`.
 *
 * The constants and the decision order are copied deliberately: Kotlin must accept
 * exactly the polygons the Rust loader accepts, otherwise a configuration can pass
 * local validation and be rejected by `setTables`.
 */
object PolygonValidator {

    /** Tolerance for a point on an edge, in normalised units. */
    const val EDGE_TOLERANCE: Double = 1e-6

    /** Minimum accepted area as a fraction of the frame. */
    const val MIN_POLYGON_AREA: Double = 0.0005

    /** Allowed polygon error in normalised units (~0.65 px at 1280 px wide). */
    const val MIN_VERTEX_COUNT: Int = 3

    fun validate(points: List<NormalizedPoint>): PolygonValidation {
        if (points.size < MIN_VERTEX_COUNT) {
            return PolygonValidation.Invalid(
                PolygonProblem.TOO_FEW_POINTS,
                "Poligon minimal $MIN_VERTEX_COUNT titik",
            )
        }

        val area = signedArea(points).absoluteValue()
        if (area < MIN_POLYGON_AREA) {
            return PolygonValidation.Invalid(
                PolygonProblem.DEGENERATE_AREA,
                "Luas poligon terlalu kecil (${formatArea(area)})",
            )
        }

        if (isSelfIntersecting(points)) {
            return PolygonValidation.Invalid(
                PolygonProblem.SELF_INTERSECTING,
                "Garis poligon saling memotong",
            )
        }

        return PolygonValidation.Valid(TablePolygon.ofValidated(points))
    }

    /** Signed area; positive for counter-clockwise rings in screen coordinates. */
    fun signedArea(points: List<NormalizedPoint>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }

    /** True when the point lies inside or on the boundary. */
    fun contains(points: List<NormalizedPoint>, point: NormalizedPoint): Boolean {
        if (points.size < 3) return false
        if (distanceToBoundary(points, point) <= EDGE_TOLERANCE) return true

        var inside = false
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val straddles = (a.y > point.y) != (b.y > point.y)
            if (!straddles) continue
            val t = (point.y - a.y) / (b.y - a.y)
            val xAtY = a.x + t * (b.x - a.x)
            if (point.x < xAtY) inside = !inside
        }
        return inside
    }

    /** Shortest distance from the point to any polygon edge. */
    fun distanceToBoundary(points: List<NormalizedPoint>, point: NormalizedPoint): Double {
        if (points.size < 2) return Double.POSITIVE_INFINITY
        var best = Double.POSITIVE_INFINITY
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            best = minOf(best, distanceToSegment(point, a, b))
        }
        return best
    }

    /** Area-weighted centroid; falls back to the vertex average for degenerate rings. */
    fun centroid(points: List<NormalizedPoint>): NormalizedPoint {
        if (points.isEmpty()) return NormalizedPoint(0.5, 0.5)
        if (points.size < 3) return averageVertex(points)

        var twiceArea = 0.0
        var cx = 0.0
        var cy = 0.0
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val cross = a.x * b.y - b.x * a.y
            twiceArea += cross
            cx += (a.x + b.x) * cross
            cy += (a.y + b.y) * cross
        }

        if (kotlin.math.abs(twiceArea) < 1e-12) return averageVertex(points)
        val factor = 1.0 / (3.0 * twiceArea)
        return NormalizedPoint(
            (cx * factor).coerceIn(0.0, 1.0),
            (cy * factor).coerceIn(0.0, 1.0),
        )
    }

    private fun averageVertex(points: List<NormalizedPoint>): NormalizedPoint {
        val x = points.sumOf { it.x } / points.size
        val y = points.sumOf { it.y } / points.size
        return NormalizedPoint.clamped(x, y)
    }

    private fun distanceToSegment(point: NormalizedPoint, a: NormalizedPoint, b: NormalizedPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-12) {
            return kotlin.math.hypot(point.x - a.x, point.y - a.y)
        }
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val px = a.x + t * dx
        val py = a.y + t * dy
        return kotlin.math.hypot(point.x - px, point.y - py)
    }

    /** Segment-intersection test mirroring `is_self_intersecting` in Rust. */
    fun isSelfIntersecting(points: List<NormalizedPoint>): Boolean {
        val count = points.size
        if (count < 4) return false

        for (i in 0 until count) {
            val a1 = points[i]
            val a2 = points[(i + 1) % count]
            for (j in (i + 1) until count) {
                if (j == i || (j + 1) % count == i || (i + 1) % count == j) continue
                val b1 = points[j]
                val b2 = points[(j + 1) % count]
                if (segmentsIntersect(a1, a2, b1, b2)) return true
            }
        }
        return false
    }

    private fun segmentsIntersect(
        p1: NormalizedPoint,
        p2: NormalizedPoint,
        p3: NormalizedPoint,
        p4: NormalizedPoint,
    ): Boolean {
        val d1 = orientation(p3, p4, p1)
        val d2 = orientation(p3, p4, p2)
        val d3 = orientation(p1, p2, p3)
        val d4 = orientation(p1, p2, p4)

        if (((d1 > 0.0 && d2 < 0.0) || (d1 < 0.0 && d2 > 0.0)) &&
            ((d3 > 0.0 && d4 < 0.0) || (d3 < 0.0 && d4 > 0.0))
        ) {
            return true
        }

        return (kotlin.math.abs(d1) <= EDGE_TOLERANCE && onSegment(p3, p4, p1)) ||
            (kotlin.math.abs(d2) <= EDGE_TOLERANCE && onSegment(p3, p4, p2)) ||
            (kotlin.math.abs(d3) <= EDGE_TOLERANCE && onSegment(p1, p2, p3)) ||
            (kotlin.math.abs(d4) <= EDGE_TOLERANCE && onSegment(p1, p2, p4))
    }

    private fun orientation(a: NormalizedPoint, b: NormalizedPoint, c: NormalizedPoint): Double =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun onSegment(a: NormalizedPoint, b: NormalizedPoint, p: NormalizedPoint): Boolean =
        p.x >= minOf(a.x, b.x) - EDGE_TOLERANCE &&
            p.x <= maxOf(a.x, b.x) + EDGE_TOLERANCE &&
            p.y >= minOf(a.y, b.y) - EDGE_TOLERANCE &&
            p.y <= maxOf(a.y, b.y) + EDGE_TOLERANCE

    private fun Double.absoluteValue(): Double = kotlin.math.abs(this)

    private fun formatArea(area: Double): String =
        java.math.BigDecimal.valueOf(area)
            .setScale(6, java.math.RoundingMode.HALF_UP)
            .toPlainString()
}