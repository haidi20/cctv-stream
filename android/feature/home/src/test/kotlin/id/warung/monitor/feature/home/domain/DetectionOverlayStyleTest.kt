package id.warung.monitor.feature.home.domain

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
}
