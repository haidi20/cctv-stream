package id.warung.monitor.service

import id.warung.monitor.core.model.TableSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification copy is the only place staff see the totals while the screen is
 * off. It must never turn "unknown" into "empty", and it must never carry a
 * camera address or credential.
 */
class MonitoringNotificationTextTest {

    @Test
    fun without_a_snapshot_it_says_it_is_waiting_instead_of_showing_zeroes() {
        val text = MonitoringNotificationText.counts(summary = null)

        assertEquals("Menunggu hitungan meja", text)
        assertFalse("a missing snapshot is not an empty warung", text.contains("0 kosong"))
    }

    @Test
    fun it_shows_empty_and_occupied_counts() {
        assertEquals(
            "4 kosong, 3 terisi",
            MonitoringNotificationText.counts(TableSummary(available = 4, occupied = 3, unknown = 0)),
        )
    }

    @Test
    fun it_calls_out_unknown_tables_separately() {
        assertEquals(
            "4 kosong, 3 terisi, 2 tidak diketahui",
            MonitoringNotificationText.counts(TableSummary(available = 4, occupied = 3, unknown = 2)),
        )
    }

    @Test
    fun a_snapshot_with_only_unknown_tables_is_not_reported_as_empty() {
        val text = MonitoringNotificationText.counts(TableSummary(available = 0, occupied = 0, unknown = 6))

        assertEquals("0 kosong, 0 terisi, 6 tidak diketahui", text)
        assertTrue("unknown tables must be visible", text.contains("6 tidak diketahui"))
    }

    @Test
    fun the_copy_never_mentions_a_stream_address() {
        val text = MonitoringNotificationText.counts(TableSummary(available = 1, occupied = 1, unknown = 0))

        assertFalse(text.contains("rtsp", ignoreCase = true))
        assertFalse(text.contains("://"))
    }
}