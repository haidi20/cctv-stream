package id.warung.monitor.feature.settings.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation runs before storage. A wrong rule either accepts plain HTTP (token
 * leak) or rejects a valid VPS URL and silently disables sync.
 */
class SyncUrlTest {

    private fun invalid(raw: String): String {
        val result = SyncUrl.validate(raw)
        assertTrue("expected invalid for '$raw'", result is SyncUrl.Result.Invalid)
        return (result as SyncUrl.Result.Invalid).reason
    }

    @Test
    fun an_https_host_is_valid_and_the_trailing_slash_is_removed() {
        assertEquals(
            SyncUrl.Result.Valid("https://vps.contoh.id"),
            SyncUrl.validate("https://vps.contoh.id/"),
        )
        assertEquals(
            SyncUrl.Result.Valid("https://vps.contoh.id:8443/monitor"),
            SyncUrl.validate("  https://vps.contoh.id:8443/monitor  "),
        )
    }

    @Test
    fun plain_http_is_rejected() {
        assertEquals("Alamat server harus diawali https://", invalid("http://vps.contoh.id"))
    }

    @Test
    fun an_empty_value_is_rejected() {
        assertEquals("Alamat server wajib diisi", invalid("   "))
    }

    @Test
    fun credentials_inside_the_url_are_rejected() {
        assertEquals(
            "Alamat server tidak boleh memuat kredensial",
            invalid("https://user:rahasia@vps.contoh.id"),
        )
    }

    @Test
    fun an_empty_host_is_rejected() {
        assertEquals("Alamat server tidak lengkap", invalid("https:///v1"))
    }

    @Test
    fun query_and_fragment_are_rejected() {
        assertEquals(
            "Alamat server tidak boleh memuat query atau fragmen",
            invalid("https://vps.contoh.id?token=abc"),
        )
        assertEquals(
            "Alamat server tidak boleh memuat query atau fragmen",
            invalid("https://vps.contoh.id#x"),
        )
    }

    @Test
    fun a_space_inside_the_url_is_rejected() {
        assertEquals(
            "Alamat server tidak boleh mengandung spasi",
            invalid("https://vps contoh.id"),
        )
    }

    @Test
    fun an_overlong_url_is_rejected() {
        assertEquals(
            "Alamat server terlalu panjang",
            invalid("https://vps.contoh.id/" + "a".repeat(SyncUrl.MAX_LENGTH)),
        )
    }

    @Test
    fun a_rejection_message_never_echoes_the_input() {
        val raw = "http://user:rahasia@vps.contoh.id"

        assertTrue(!invalid(raw).contains("rahasia"))
        assertTrue(!invalid(raw).contains("vps.contoh.id"))
    }
}
