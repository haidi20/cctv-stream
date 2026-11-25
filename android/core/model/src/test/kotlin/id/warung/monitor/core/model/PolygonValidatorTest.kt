package id.warung.monitor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonValidatorTest {

    private fun square(): List<NormalizedPoint> = listOf(
        NormalizedPoint(0.2, 0.2),
        NormalizedPoint(0.4, 0.2),
        NormalizedPoint(0.4, 0.4),
        NormalizedPoint(0.2, 0.4),
    )

    @Test
    fun rejects_polygons_with_too_few_points() {
        val result = PolygonValidator.validate(
            listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.2, 0.2)),
        )
        assertEquals(
            PolygonProblem.TOO_FEW_POINTS,
            (result as PolygonValidation.Invalid).problem,
        )
    }

    @Test
    fun rejects_degenerate_area() {
        val result = PolygonValidator.validate(
            listOf(
                NormalizedPoint(0.1, 0.1),
                NormalizedPoint(0.1 + 0.01, 0.1),
                NormalizedPoint(0.1 + 0.02, 0.1),
            ),
        )
        assertEquals(
            PolygonProblem.DEGENERATE_AREA,
            (result as PolygonValidation.Invalid).problem,
        )
    }

    @Test
    fun rejects_self_intersecting_outline() {
        val bowtie = listOf(
            NormalizedPoint(0.1, 0.1),
            NormalizedPoint(0.4, 0.4),
            NormalizedPoint(0.4, 0.1),
            NormalizedPoint(0.1, 0.4),
            NormalizedPoint(0.0, 0.0),
        )
        val result = PolygonValidator.validate(bowtie)
        assertEquals(
            PolygonProblem.SELF_INTERSECTING,
            (result as PolygonValidation.Invalid).problem,
        )
    }

    @Test
    fun accepts_simple_rectangle() {
        val result = PolygonValidator.validate(square())
        assertTrue(result is PolygonValidation.Valid)
        assertEquals(0.04, (result as PolygonValidation.Valid).polygon.area, 1e-12)
    }

    @Test
    fun area_matches_rust_threshold() {
        assertEquals(0.0005, PolygonValidator.MIN_POLYGON_AREA, 0.0)
        assertEquals(1e-6, PolygonValidator.EDGE_TOLERANCE, 0.0)
    }

    @Test
    fun point_on_edge_counts_as_inside() {
        assertTrue(PolygonValidator.contains(square(), NormalizedPoint(0.2, 0.3)))
        assertTrue(PolygonValidator.contains(square(), NormalizedPoint(0.3, 0.4)))
        assertTrue(PolygonValidator.contains(square(), NormalizedPoint(0.3, 0.3)))
        assertFalse(PolygonValidator.contains(square(), NormalizedPoint(0.5, 0.3)))
    }

    @Test
    fun edge_tolerance_covers_near_edge_points() {
        val justOutside = NormalizedPoint(0.2 - 5e-7, 0.3)
        assertTrue(PolygonValidator.contains(square(), justOutside))
        val clearlyOutside = NormalizedPoint(0.2 - 5e-5, 0.3)
        assertFalse(PolygonValidator.contains(square(), clearlyOutside))
    }

    @Test
    fun centroid_of_rectangle_is_its_center() {
        val centroid = PolygonValidator.centroid(square())
        assertEquals(0.3, centroid.x, 1e-12)
        assertEquals(0.3, centroid.y, 1e-12)
    }

    @Test
    fun reversed_winding_has_same_area() {
        val reversed = square().reversed()
        val area = kotlin.math.abs(PolygonValidator.signedArea(reversed))
        assertEquals(0.04, area, 1e-12)
    }
}