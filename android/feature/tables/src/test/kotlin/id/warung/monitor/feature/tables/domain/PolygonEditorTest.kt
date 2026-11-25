package id.warung.monitor.feature.tables.domain

import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.PolygonProblem
import id.warung.monitor.core.model.TableId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonEditorTest {

    private fun square(): List<NormalizedPoint> = listOf(
        NormalizedPoint(0.2, 0.2),
        NormalizedPoint(0.4, 0.2),
        NormalizedPoint(0.4, 0.4),
        NormalizedPoint(0.2, 0.4),
    )

    @Test
    fun next_id_skips_used_numbers() {
        val existing = listOf(TableId("meja-01"), TableId("meja-03"))
        assertEquals("meja-02", PolygonEditor.nextId(existing).value)
        assertEquals("meja-01", PolygonEditor.nextId(emptyList()).value)
    }

    @Test
    fun next_id_ignores_custom_ids() {
        assertEquals("meja-01", PolygonEditor.nextId(listOf(TableId("pintu"))).value)
    }

    @Test
    fun adding_a_point_quantises_to_four_decimals() {
        val result = PolygonEditor.addVertex(
            emptyList(),
            NormalizedPoint(0.123456, 0.987654),
        )
        val points = (result as PolygonEditResult.Success).points
        assertEquals(0.1235, points.single().x, 0.0)
        assertEquals(0.9877, points.single().y, 0.0)
    }

    @Test
    fun a_partial_polygon_may_still_be_edited() {
        val twoPoints = listOf(NormalizedPoint(0.2, 0.2), NormalizedPoint(0.4, 0.2))
        val result = PolygonEditor.addVertex(twoPoints, NormalizedPoint(0.4, 0.4))
        assertTrue(result is PolygonEditResult.Success)
    }

    @Test
    fun moving_a_vertex_keeps_the_polygon_valid() {
        val result = PolygonEditor.moveVertex(square(), 0, NormalizedPoint(0.1, 0.1))
        val points = (result as PolygonEditResult.Success).points
        assertEquals(NormalizedPoint(0.1, 0.1), points.first())
    }

    @Test
    fun moving_a_vertex_into_a_crossing_is_rejected() {
        val result = PolygonEditor.moveVertex(square(), 1, NormalizedPoint(0.1, 0.4))
        assertTrue(result is PolygonEditResult.Rejected)
    }

    @Test
    fun removing_below_three_points_is_rejected() {
        val triangle = listOf(
            NormalizedPoint(0.2, 0.2),
            NormalizedPoint(0.4, 0.2),
            NormalizedPoint(0.4, 0.4),
        )
        val result = PolygonEditor.removeVertex(triangle, 0)
        val rejected = result as PolygonEditResult.Rejected
        assertEquals(PolygonProblem.TOO_FEW_POINTS, rejected.problem)
    }

    @Test
    fun removing_a_vertex_from_a_square_succeeds() {
        val result = PolygonEditor.removeVertex(square(), 1)
        val points = (result as PolygonEditResult.Success).points
        assertEquals(3, points.size)
    }

    @Test
    fun collapsing_into_a_degenerate_shape_is_rejected() {
        val square = square()
        val result = PolygonEditor.moveVertex(square, 1, square[0])
        assertTrue(result is PolygonEditResult.Rejected)
    }
}