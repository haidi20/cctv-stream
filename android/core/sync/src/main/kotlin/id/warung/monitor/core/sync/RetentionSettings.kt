package id.warung.monitor.core.sync

/**
 * How long locally recorded table history is kept (`docs/android-sync.md`
 * sections 6 and 7).
 *
 * Only the four documented choices exist: an unknown stored value falls back to
 * [DEFAULT] instead of inventing a fifth window, so a build that adds a value
 * and is then downgraded still opens the settings screen.
 */
enum class RetentionWindow(val days: Int) {
    THREE_DAYS(3),
    SEVEN_DAYS(7),
    FOURTEEN_DAYS(14),
    THIRTY_DAYS(30),
    ;

    companion object {
        val DEFAULT: RetentionWindow = SEVEN_DAYS

        fun fromDays(days: Int?): RetentionWindow =
            entries.firstOrNull { it.days == days } ?: DEFAULT
    }
}

/**
 * Thresholds shared by the prune worker and the storage screen.
 *
 * Keeping them here, next to the window, means the DAO, the worker, and the UI
 * cannot drift: `table_logs` is pruned by age *and* by row count, and both
 * numbers come from one place.
 */
object RetentionPolicy {

    /** Newest rows always kept; the rest of the history is dropped FIFO. */
    const val MAX_ROWS: Int = 10_000

    const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    /** Exclusive lower bound: rows older than this may be pruned. */
    fun cutoffMillis(nowMillis: Long, window: RetentionWindow): Long =
        nowMillis - window.days * DAY_MILLIS
}

/** Local history counters for the "Penyimpanan & Riwayat" card. */
data class LocalHistoryStats(
    val rows: Int,
    val databaseBytes: Long,
    val rowLimit: Int = RetentionPolicy.MAX_ROWS,
)

/** Read/write seam for the user's retention choice (`android-sync.md` section 7). */
interface RetentionSettings {

    suspend fun window(): RetentionWindow

    suspend fun save(window: RetentionWindow)
}

/**
 * Read-only view of the local history.
 *
 * The settings screen only reports; deleting history stays the worker's job so
 * changing a value never blocks the UI on a large `DELETE`.
 */
interface LocalHistoryStore {

    suspend fun stats(): LocalHistoryStats
}
