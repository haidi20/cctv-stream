package id.warung.monitor.feature.home.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The card's aspect ratio is the only thing that makes a normalised `0..1`
 * detection box land on the person (Q-01), so the conversion is tested on its
 * own: 16:9 keeps the fallback shape, 4:3 and portrait keep their own, and an
 * unmeasured size must never become an infinite ratio.
 */
class VideoAspectTest {

    @Test
    fun a_sixteen_by_nine_frame_matches_the_fallback_shape() {
        assertEquals(VideoAspect.Fallback, VideoAspect.of(1920, 1080)!!, 1e-4f)
    }

    @Test
    fun a_four_by_three_stream_reports_its_own_shape() {
        assertEquals(4f / 3f, VideoAspect.of(640, 480)!!, 1e-4f)
    }

    @Test
    fun a_portrait_stream_is_taller_than_wide() {
        assertEquals(9f / 16f, VideoAspect.of(1080, 1920)!!, 1e-4f)
    }

    @Test
    fun a_size_media3_has_not_measured_yet_has_no_aspect() {
        assertNull(VideoAspect.of(0, 0))
        assertNull(VideoAspect.of(640, 0))
        assertNull(VideoAspect.of(-640, 480))
    }
}
