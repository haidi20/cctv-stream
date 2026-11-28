package id.warung.monitor.feature.home.domain

import id.warung.monitor.feature.cameras.session.CountingReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wording guard for the "why is nothing counted" line.
 *
 * Each reason must read differently; a shared generic sentence would hide the
 * difference between "install the model" and "draw the map"
 * (`docs/kotlin-rust-gaps.md` G-02).
 */
class CountingNoticeTest {

    @Test
    fun every_reason_gets_its_own_sentence() {
        val messages = CountingReason.entries.map(CountingNotice::message)
        assertEquals(CountingReason.entries.size, messages.toSet().size)
    }

    @Test
    fun every_reason_says_detection_is_inactive() {
        CountingReason.entries.forEach { reason ->
            assertTrue(
                "missing prefix for $reason",
                CountingNotice.message(reason).startsWith("Deteksi tidak aktif: "),
            )
        }
    }

    @Test
    fun every_reason_gets_a_distinct_hint() {
        val hints = CountingReason.entries.map(CountingNotice::hint)
        assertEquals(CountingReason.entries.size, hints.toSet().size)
    }

    @Test
    fun only_a_missing_map_is_fixed_by_drawing_polygons() {
        assertTrue(CountingNotice.needsMapping(CountingReason.NoTables))
        CountingReason.entries
            .filter { it != CountingReason.NoTables }
            .forEach { assertFalse("$it must not offer the map action", CountingNotice.needsMapping(it)) }
    }
}
