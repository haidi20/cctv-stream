package id.warung.monitor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingFrameTest {

    private val frame = MappingFrame(1280, 720, "16:9")

    @Test
    fun canonical_ratio_is_reduced() {
        assertEquals("16:9", MappingFrame.canonicalAspectRatio(1280, 720))
        assertEquals("4:3", MappingFrame.canonicalAspectRatio(640, 480))
        assertEquals("16:9", MappingFrame.canonicalAspectRatio(1920, 1080))
    }

    @Test
    fun same_ratio_survives_resolution_change() {
        assertFalse(needsRecalibration(frame, 1920, 1080))
        assertFalse(needsRecalibration(frame, 960, 540))
        assertFalse(needsRecalibration(frame, 1280, 720))
    }

    @Test
    fun anamorphic_resolution_needs_recalibration() {
        assertTrue(needsRecalibration(frame, 854, 480))
        assertTrue(needsRecalibration(frame, 640, 480))
    }

    @Test
    fun invalid_ratio_is_rejected() {
        assertNull(parseAspectRatio("16"))
        assertNull(parseAspectRatio("16:"))
        assertNull(parseAspectRatio("0:9"))
        assertNull(parseAspectRatio("a:b"))
        assertEquals(ParsedAspectRatio(16, 9), parseAspectRatio(" 16 : 9 "))
    }

    @Test
    fun zero_dimensions_are_rejected() {
        assertFalse(frame.matchesAspectRatio(0, 720))
        assertFalse(frame.matchesAspectRatio(1280, 0))
    }
}