package id.warung.monitor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraProfileTest {

    private fun rtsp(
        host: String = "192.168.1.64",
        port: Int = 554,
        path: String = "/h264/ch1/sub/av_stream",
        username: String? = "viewer",
    ) = CameraSource.Rtsp(host, port, path, username)

    @Test
    fun camera_id_accepts_stable_uuid_like_values() {
        val id = CameraId("cam-warung.depan_01")
        assertEquals("cam-warung.depan_01", id.value)
    }

    @Test
    fun camera_id_rejects_blank_and_overlong_values() {
        assertThrows(IllegalArgumentException::class.java) { CameraId("   ") }
        assertThrows(IllegalArgumentException::class.java) { CameraId("c".repeat(65)) }
    }

    @Test
    fun camera_id_rejects_anything_that_could_carry_a_credential() {
        listOf(
            "rtsp://user:pass@192.168.1.64/stream",
            "camera one",
            "camera/one",
            "camera@one",
            "kamera-ü",
        ).forEach { candidate ->
            assertThrows(candidate, IllegalArgumentException::class.java) { CameraId(candidate) }
        }
    }

    @Test
    fun label_is_trimmed_and_bounded() {
        assertEquals(40, CameraLabel("x".repeat(40)).value.length)
        assertThrows(IllegalArgumentException::class.java) { CameraLabel(" ") }
        assertThrows(IllegalArgumentException::class.java) { CameraLabel("y".repeat(41)) }
    }

    @Test
    fun rtsp_source_has_no_password_field_and_keeps_only_safe_parts() {
        val source = rtsp()
        assertEquals("192.168.1.64", source.host)
        assertEquals(554, source.port)
        assertEquals("/h264/ch1/sub/av_stream", source.path)
        assertEquals("viewer", source.username)
        assertEquals("RTSP - 192.168.1.64:554", profile(source).sourceDescription)
    }

    @Test
    fun rtsp_source_rejects_scheme_credentials_and_bad_ports() {
        assertThrows(IllegalArgumentException::class.java) { rtsp(host = "rtsp://192.168.1.64") }
        assertThrows(IllegalArgumentException::class.java) { rtsp(host = "viewer@192.168.1.64") }
        assertThrows(IllegalArgumentException::class.java) { rtsp(port = 0) }
        assertThrows(IllegalArgumentException::class.java) { rtsp(port = 70_000) }
        assertThrows(IllegalArgumentException::class.java) { rtsp(path = "stream") }
        assertThrows(IllegalArgumentException::class.java) { rtsp(path = "/user:pass@host/stream") }
        assertThrows(IllegalArgumentException::class.java) { rtsp(username = "user:pass") }
    }

    @Test
    fun demo_source_is_an_explicit_choice_not_a_fallback_for_errors() {
        assertEquals("Demo", profile(CameraSource.Demo).sourceDescription)
        assertFalse(CameraSource.Demo is CameraSource.Rtsp)
        assertTrue(rtsp() is CameraSource.Rtsp)
    }

    private fun profile(source: CameraSource) = CameraProfile(
        id = CameraId("cam-1"),
        label = CameraLabel("Warung Depan"),
        source = source,
        createdAt = 1L,
        updatedAt = 1L,
    )
}