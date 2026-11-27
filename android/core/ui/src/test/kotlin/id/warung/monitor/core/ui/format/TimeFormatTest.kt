package id.warung.monitor.core.ui.format

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    private val zone = ZoneId.of("Asia/Makassar")

    @Test
    fun date_time_includes_the_local_calendar_date() {
        val epochMillis = 1_700_000_000_000L

        assertEquals("2023-11-15 06:13:20", TimeFormat.dateTime(epochMillis, zone))
        assertEquals("2023-11-14 22:13:20", TimeFormat.dateTime(epochMillis, ZoneId.of("UTC")))
    }

    @Test
    fun clock_uses_two_digit_24_hour_local_time() {
        val epochMillis = 1_700_000_000_000L

        assertEquals("06:13:20", TimeFormat.clock(epochMillis, zone))
    }

    @Test
    fun clock_uses_the_callers_zone_instead_of_the_device_default() {
        val epochMillis = 1_700_000_000_000L

        assertEquals("22:13:20", TimeFormat.clock(epochMillis, ZoneId.of("UTC")))
    }

    @Test
    fun age_under_a_second_reads_baru_saja() {
        assertEquals("baru saja", TimeFormat.age(1_000L, 1_999L, zone))
    }

    @Test
    fun age_in_minutes_never_prints_a_bare_second_count() {
        assertEquals("1 menit lalu", TimeFormat.age(0L, 60_000L, zone))
        assertEquals("12 detik lalu", TimeFormat.age(0L, 12_500L, zone))
    }

    @Test
    fun age_of_an_hour_or_more_falls_back_to_the_clock() {
        assertEquals("08:00:00", TimeFormat.age(0L, 3_600_000L, ZoneId.of("Asia/Makassar")))
    }

    @Test
    fun age_ignores_clock_skew_instead_of_printing_a_negative_number() {
        assertEquals("baru saja", TimeFormat.age(5_000L, 1_000L, zone))
    }
}