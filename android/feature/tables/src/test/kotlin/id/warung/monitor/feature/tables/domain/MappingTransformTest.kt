package id.warung.monitor.feature.tables.domain

import id.warung.monitor.core.model.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class MappingTransformTest {

    private val frame = 1280 to 720
    private val viewport = ScreenSize(1080f, 608f)

    private fun transform(): MappingTransform =
        MappingTransform(viewport, frame.first, frame.second)

    @Test
    fun fit_scale_and_letterbox_match_the_contract() {
        val active = transform()
        assertEquals(0.84375f, active.base, 1e-6f)
        assertEquals(0f, active.content.x, 1e-4f)
        assertEquals(0.25f, active.content.y, 1e-4f)
    }

    @Test
    fun identity_mapping_at_zoom_1x() {
        val active = transform()
        val screen = active.toScreen(NormalizedPoint(0.5, 0.5), 1f, ScreenPan.None)
        assertEquals(540f, screen.x, 1e-4f)
        assertEquals(304f, screen.y, 1e-4f)
    }

    @Test
    fun round_trip_keeps_point() {
        val active = transform()
        val original = NormalizedPoint(0.31, 0.62)
        for (zoom in listOf(1f, 3f, 8f)) {
            val pan = active.centeredPan(zoom)
            val screen = active.toScreen(original, zoom, pan)
            val back = active.toNormalized(screen, zoom, pan)
            assertEquals(original.x, back.x, 1e-4)
            assertEquals(original.y, back.y, 1e-4)
        }
    }

    @Test
    fun touch_in_letterbox_is_clamped() {
        val active = transform()
        val point = active.toNormalized(ScreenPoint(-50f, 2000f), 1f, ScreenPan.None)
        assertEquals(0.0, point.x, 1e-9)
        assertEquals(1.0, point.y, 1e-9)
    }

    @Test
    fun pan_is_clamped_to_image_bounds() {
        val active = transform()
        val zoom = 3f
        val centered = active.centeredPan(zoom)
        assertEquals(-1080f, centered.x, 1e-3f)
        assertEquals(-607.5f, centered.y, 1e-3f)

        val atUpper = active.clampPan(ScreenPan(10_000f, -10_000f), zoom)
        assertEquals(0f, atUpper.x, 1e-3f)
        assertEquals(-1214.75f, atUpper.y, 1e-3f)

        val atLower = active.clampPan(ScreenPan(-10_000f, 10_000f), zoom)
        assertEquals(-2160f, atLower.x, 1e-3f)
        assertEquals(-0.25f, atLower.y, 1e-3f)
    }

    @Test
    fun zoom_around_center_keeps_center() {
        val active = transform()
        val center = ScreenPoint(540f, 304f)
        val pan = active.zoomAnchored(center, 1f, 8f, ScreenPan.None)
        assertEquals(-3780f, pan.x, 1e-3f)
        assertEquals(-2126.25f, pan.y, 1e-3f)
        val screen = active.toScreen(NormalizedPoint(0.5, 0.5), 8f, pan)
        assertEquals(540f, screen.x, 1e-2f)
        assertEquals(304f, screen.y, 1e-2f)
    }

    @Test
    fun single_axis_letterbox_pans_only_on_the_large_axis() {
        val active = MappingTransform(ScreenSize(1080f, 2400f), 1280, 720)
        val pan = active.centeredPan(2f)
        assertEquals(-540f, pan.x, 1e-3f)
        assertEquals(0f, pan.y, 1e-3f)
    }

    @Test
    fun zoom_is_clamped_between_one_and_eight() {
        val active = transform()
        assertEquals(1f, MappingTransform.MIN_ZOOM, 0f)
        assertEquals(8f, MappingTransform.MAX_ZOOM, 0f)
        val clamped = active.zoomAnchored(ScreenPoint(540f, 304f), 1f, 32f, ScreenPan.None)
        val screen = active.toScreen(NormalizedPoint(0.5, 0.5), 8f, clamped)
        assertEquals(540f, screen.x, 1e-2f)
    }
}