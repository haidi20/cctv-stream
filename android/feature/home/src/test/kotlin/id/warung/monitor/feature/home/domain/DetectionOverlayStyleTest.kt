package id.warung.monitor.feature.home.domain

import androidx.compose.ui.geometry.Offset
import id.warung.monitor.core.model.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionOverlayStyleTest {

    private fun rect(left: Float, top: Float, width: Float, height: Float, confidence: Float) =
        DetectionRect(left = left, top = top, width = width, height = height, confidence = confidence)

    @Test
    fun overlay_stroke_is_density_scaled() {
        assertEquals(2f, DetectionOverlayStyle.strokeWidthPx(1f), 1e-6f)
        assertEquals(6f, DetectionOverlayStyle.strokeWidthPx(3f), 1e-6f)
        assertEquals(0f, DetectionOverlayStyle.strokeWidthPx(-1f), 1e-6f)
    }

    @Test
    fun overlay_clips_boxes_to_video_area() {
        val overhang = rect(left = 0.9f, top = 0.9f, width = 0.4f, height = 0.4f, confidence = 0.8f)
        val clipped = DetectionOverlayStyle.clippedToFrame(overhang)!!
        assertEquals(0.9f, clipped.left, 1e-6f)
        assertEquals(0.1f, clipped.width, 1e-6f)
        assertEquals(0.1f, clipped.height, 1e-6f)

        val outside = rect(left = -0.5f, top = 0.2f, width = 0.2f, height = 0.3f, confidence = 0.8f)
        assertNull(
            "a box entirely left of the frame has no area to draw",
            DetectionOverlayStyle.clippedToFrame(outside),
        )

        // A box fully inside the frame is returned unchanged. Fields are compared
        // with a tolerance: `left + width` is a float round trip, so exact
        // equality would make this test depend on rounding, not on clipping.
        val inside = rect(left = 0.1f, top = 0.1f, width = 0.2f, height = 0.2f, confidence = 0.8f)
        val unchanged = DetectionOverlayStyle.clippedToFrame(inside)!!
        assertEquals(inside.left, unchanged.left, 1e-6f)
        assertEquals(inside.top, unchanged.top, 1e-6f)
        assertEquals(inside.width, unchanged.width, 1e-6f)
        assertEquals(inside.height, unchanged.height, 1e-6f)
        assertEquals(inside.confidence, unchanged.confidence, 1e-6f)
    }

    @Test
    fun a_box_younger_than_the_budget_is_drawn() {
        val box = DetectionRect(0.1f, 0.2f, 0.3f, 0.4f, 0.9f, ageMillis = 900L)

        assertEquals(listOf(box), DetectionOverlayStyle.freshBoxes(listOf(box)))
        assertTrue(DetectionOverlayStyle.isStale(900L).not())
    }

    @Test
    fun a_box_older_than_the_budget_is_hidden() {
        val box = DetectionRect(0.1f, 0.2f, 0.3f, 0.4f, 0.9f, ageMillis = 1_201L)

        assertTrue(DetectionOverlayStyle.isStale(1_201L))
        assertTrue(
            "a box that trails the person must not be drawn",
            DetectionOverlayStyle.freshBoxes(listOf(box)).isEmpty(),
        )
        assertTrue(
            "the boundary itself is still current",
            DetectionOverlayStyle.isStale(DetectionOverlayStyle.StaleAfterMillis).not(),
        )
    }

    @Test
    fun a_box_with_no_measured_age_is_not_drawn() {
        val unknown = DetectionRect(0.1f, 0.2f, 0.3f, 0.4f, 0.9f, ageMillis = null)

        assertTrue(
            "an unmeasured box has no evidence it is current",
            DetectionOverlayStyle.freshBoxes(listOf(unknown)).isEmpty(),
        )
    }

    @Test
    fun low_confidence_box_is_dimmer() {
        val weak = DetectionOverlayStyle.alphaFor(0.36f)
        val strong = DetectionOverlayStyle.alphaFor(0.92f)
        assertEquals(DetectionOverlayStyle.MinAlpha, DetectionOverlayStyle.alphaFor(0f), 1e-6f)
        assertEquals(1f, DetectionOverlayStyle.alphaFor(1f), 1e-6f)
        assertTrue("0.36 was $weak, 0.92 was $strong", weak < strong)
        assertTrue(DetectionOverlayStyle.alphaFor(2f) <= 1f)
        assertTrue(DetectionOverlayStyle.alphaFor(Float.NaN) >= DetectionOverlayStyle.MinAlpha)
    }

    private fun square(x: Double = 0.1, y: Double = 0.1, size: Double = 0.2) = listOf(
        NormalizedPoint(x, y),
        NormalizedPoint(x + size, y),
        NormalizedPoint(x + size, y + size),
        NormalizedPoint(x, y + size),
    )

    @Test
    fun the_scope_outline_is_thinner_than_the_person_box() {
        assertEquals(1.5f, DetectionOverlayStyle.scopeStrokeWidthPx(1f), 1e-6f)
        assertEquals(4.5f, DetectionOverlayStyle.scopeStrokeWidthPx(3f), 1e-6f)
        assertEquals(0f, DetectionOverlayStyle.scopeStrokeWidthPx(-2f), 1e-6f)
        assertTrue(
            "the map is the backdrop, the detection is the foreground",
            DetectionOverlayStyle.ScopeStrokeDp < DetectionOverlayStyle.StrokeDp,
        )
    }

    @Test
    fun the_scope_is_blue_and_the_person_box_stays_green() {
        // 0xFF3B82F6: blue channel is the largest component, red the smallest.
        val argb = DetectionOverlayStyle.ScopeColorArgb
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        assertTrue("blue must dominate: r=$red g=$green b=$blue", blue > green && blue > red)
        assertEquals(0xFFL, (argb shr 24) and 0xFF)
    }

    @Test
    fun a_footprint_needs_three_points_to_be_drawn() {
        assertTrue(DetectionOverlayStyle.isDrawableScope(square()))
        assertTrue(DetectionOverlayStyle.isDrawableScope(square().take(3)))
        assertTrue("two points are a line, not a table", !DetectionOverlayStyle.isDrawableScope(square().take(2)))
        assertTrue("one point is a dot, not a table", !DetectionOverlayStyle.isDrawableScope(square().take(1)))
        assertTrue(!DetectionOverlayStyle.isDrawableScope(emptyList()))
    }

    @Test
    fun degenerate_footprints_are_dropped_from_the_overlay() {
        val polygons = listOf(square(), emptyList(), square().take(2), square(0.5, 0.5))

        val drawable = DetectionOverlayStyle.drawableScopes(polygons)

        assertEquals(2, drawable.size)
        assertEquals(square(), drawable[0])
        assertEquals(square(0.5, 0.5), drawable[1])
    }

    @Test
    fun an_absurd_map_is_capped_so_the_overlay_stays_cheap() {
        val many = List(DetectionOverlayStyle.MaxScopePolygons + 25) { square() }

        assertEquals(
            DetectionOverlayStyle.MaxScopePolygons,
            DetectionOverlayStyle.drawableScopes(many).size,
        )
        assertTrue(DetectionOverlayStyle.drawableScopes(emptyList()).isEmpty())
    }

    @Test
    fun scope_points_are_projected_into_pixel_space() {
        val points = DetectionOverlayStyle.scopePathPoints(square(), width = 640f, height = 360f)!!

        // Float multiplication of a normalised coordinate is not exact, so the
        // assertion is about the projection, not about float bit patterns.
        val expected = listOf(Offset(64f, 36f), Offset(192f, 36f), Offset(192f, 108f), Offset(64f, 108f))
        assertEquals(expected.size, points.size)
        expected.zip(points).forEach { (want, got) ->
            assertEquals(want.x, got.x, 1e-3f)
            assertEquals(want.y, got.y, 1e-3f)
        }
    }

    @Test
    fun scope_points_are_skipped_without_a_measurable_frame() {
        assertNull(DetectionOverlayStyle.scopePathPoints(square(), width = 0f, height = 360f))
        assertNull(DetectionOverlayStyle.scopePathPoints(square(), width = 640f, height = -1f))
        assertNull(DetectionOverlayStyle.scopePathPoints(square(), width = Float.NaN, height = 360f))
        assertNull("a two-point scope has no area", DetectionOverlayStyle.scopePathPoints(square().take(2), 640f, 360f))
    }
}
