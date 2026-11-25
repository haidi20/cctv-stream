package id.warung.monitor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NormalizedPointTest {

    @Test
    fun quantizes_to_four_decimals_half_up() {
        assertEquals(0.1235, NormalizedPoint(0.12345, 0.0).x.round4(), 0.0)
        assertEquals(0.1234, NormalizedPoint(0.12344, 0.0).x.round4(), 0.0)
        assertEquals(1.0, NormalizedPoint(0.99999, 0.0).x.round4(), 0.0)
        assertEquals(0.0, NormalizedPoint(0.00001, 0.0).x.round4(), 0.0)
    }

    @Test
    fun quantization_never_leaves_the_valid_range() {
        val point = NormalizedPoint(0.99999, 0.00001).quantized()
        assertEquals(1.0, point.x, 0.0)
        assertEquals(0.0, point.y, 0.0)
    }

    @Test
    fun rejects_out_of_range_on_decode() {
        assertNull(NormalizedPoint.orNull(-0.1, 0.5))
        assertNull(NormalizedPoint.orNull(0.5, 1.1))
        assertNull(NormalizedPoint.orNull(Double.NaN, 0.5))
        assertNull(NormalizedPoint.orNull(0.5, Double.POSITIVE_INFINITY))
    }

    @Test
    fun accepts_boundary_values() {
        assertNotNull(NormalizedPoint.orNull(0.0, 1.0))
        assertNotNull(NormalizedPoint.orNull(1.0, 0.0))
    }

    @Test
    fun clamps_screen_space_inputs() {
        val point = NormalizedPoint.clamped(-50.0, 2.0)
        assertEquals(0.0, point.x, 0.0)
        assertEquals(1.0, point.y, 0.0)
    }
}