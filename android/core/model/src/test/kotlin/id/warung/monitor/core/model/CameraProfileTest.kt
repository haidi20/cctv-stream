package id.warung.monitor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun a_device_id_keeps_a_stable_uuid_but_rejects_an_ip_or_credential() {
        val stable = CameraDeviceId.from("  urn:uuid:3F2504E0-4F89-11D3-9A0C-0305E82C3301  ")
        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", stable?.value)

        listOf(
            "192.168.1.64",
            "192.168.1.64:554",
            "rtsp://admin:rahasia@192.168.1.64/live",
            "admin:rahasia@192.168.1.64",
            "camera satu",
            "a".repeat(129),
        ).forEach { candidate -> assertNull(CameraDeviceId.from(candidate)) }
        assertNull(CameraDeviceId.from(null))
    }

    @Test
    fun device_id_is_case_insensitive_and_never_equals_an_endpoint() {
        val upper = CameraDeviceId.from("3F2504E0-4F89-11D3-9A0C-0305E82C3301")
        val lower = CameraDeviceId.from("urn:uuid:3f2504e0-4f89-11d3-9a0c-0305e82c3301")

        assertEquals(lower, upper)
        assertTrue(upper != CameraDeviceId.from("192.168.1.64"))
    }

    @Test
    fun a_camera_profile_keeps_the_device_id_when_the_ip_changes() {
        val deviceId = CameraDeviceId.from("urn:uuid:3f2504e0-4f89-11d3-9a0c-0305e82c3301")
        val original = profile(rtsp()).copy(deviceId = deviceId)

        val moved = original.copy(source = CameraSource.Rtsp("192.168.1.99", 554, "/live"))

        assertEquals(deviceId, moved.deviceId)
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
        val demo: CameraSource = CameraSource.Demo
        assertFalse(demo is CameraSource.Rtsp)
        val endpoint: CameraSource = rtsp()
        assertTrue(endpoint is CameraSource.Rtsp)
    }

    @Test
    fun video_source_accepts_only_a_bare_mp4_file_name() {
        assertEquals("Video - clip.mp4", profile(CameraSource.Video("clip.mp4")).sourceDescription)
        assertThrows(IllegalArgumentException::class.java) { CameraSource.Video("") }
        assertThrows(IllegalArgumentException::class.java) { CameraSource.Video("../clip.mp4") }
        assertThrows(IllegalArgumentException::class.java) { CameraSource.Video("clips/clip.mp4") }
        assertThrows(IllegalArgumentException::class.java) { CameraSource.Video("C:\\clip.mp4") }
        assertThrows(IllegalArgumentException::class.java) { CameraSource.Video("clip 1.mp4") }
        assertThrows(IllegalArgumentException::class.java) { CameraSource.Video("clip.mkv") }
        assertThrows(IllegalArgumentException::class.java) {
            CameraSource.Video("x".repeat(117) + ".mp4")
        }
    }

    private fun profile(source: CameraSource) = CameraProfile(
        id = CameraId("cam-1"),
        label = CameraLabel("Warung Depan"),
        source = source,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
