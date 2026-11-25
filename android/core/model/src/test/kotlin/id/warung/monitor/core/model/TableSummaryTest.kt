package id.warung.monitor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TableSummaryTest {

    @Test
    fun total_includes_unknown_so_configuration_is_not_lost() {
        val summary = TableSummary(available = 12, occupied = 3, unknown = 1)
        assertEquals(16, summary.total)
    }

    @Test
    fun unknown_is_not_counted_as_available() {
        val summary = TableSummary(available = 0, occupied = 0, unknown = 5)
        assertEquals(0, summary.known)
        assertEquals(5, summary.total)
        assertEquals(0, summary.available)
    }

    @Test
    fun negative_counts_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) { TableSummary(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TableSummary(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { TableSummary(0, 0, -1) }
    }
}