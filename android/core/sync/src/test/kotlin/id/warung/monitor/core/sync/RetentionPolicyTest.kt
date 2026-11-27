package id.warung.monitor.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cutoff is the only thing standing between a long-running warung and an
 * unbounded `table_logs`, so its arithmetic is pinned instead of assumed.
 */
class RetentionPolicyTest {

    @Test
    fun the_default_window_is_seven_days() {
        assertEquals(7, RetentionWindow.DEFAULT.days)
        assertEquals(RetentionWindow.SEVEN_DAYS, RetentionWindow.fromDays(null))
    }

    @Test
    fun every_documented_window_is_offered_and_unknown_values_fall_back() {
        assertEquals(3, RetentionWindow.THREE_DAYS.days)
        assertEquals(14, RetentionWindow.FOURTEEN_DAYS.days)
        assertEquals(30, RetentionWindow.THIRTY_DAYS.days)
        assertEquals(RetentionWindow.SEVEN_DAYS, RetentionWindow.fromDays(5))
        assertEquals(RetentionWindow.THIRTY_DAYS, RetentionWindow.fromDays(30))
    }

    @Test
    fun the_cutoff_moves_back_by_the_full_window() {
        val now = 1_000L * RetentionPolicy.DAY_MILLIS

        assertEquals(
            now - 7 * RetentionPolicy.DAY_MILLIS,
            RetentionPolicy.cutoffMillis(now, RetentionWindow.SEVEN_DAYS),
        )
        assertEquals(
            now - 3 * RetentionPolicy.DAY_MILLIS,
            RetentionPolicy.cutoffMillis(now, RetentionWindow.THREE_DAYS),
        )
    }

    @Test
    fun history_is_capped_at_ten_thousand_rows_regardless_of_age() {
        assertEquals(10_000, RetentionPolicy.MAX_ROWS)
        assertEquals(10_000, LocalHistoryStats(rows = 0, databaseBytes = 0).rowLimit)
    }
}
