package id.warung.monitor.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `table_logs` is the only prunable table, so these tests lock what a prune is
 * allowed to delete: never an unacknowledged row, never the newest rows, and
 * always in one of two bounded statements.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TableLogDaoTest {

    private lateinit var database: MonitorDatabase
    private lateinit var dao: TableLogDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MonitorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.tableLogs()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun row(
        tableId: String = "table-1",
        status: String = "OCCUPIED",
        eventAt: Long = 1L,
    ) = TableLogEntity(tableId = tableId, status = status, eventAt = eventAt)

    @Test
    fun history_rows_are_appended_and_counted() = runTest {
        assertEquals(1L, dao.insert(row(tableId = "meja-1")))
        assertEquals(2L, dao.insert(row(tableId = "meja-2", status = "AVAILABLE")))

        assertEquals(2, dao.count())
        assertEquals(0, dao.syncedCount())
        assertEquals(listOf("meja-2", "meja-1"), dao.recent(limit = 10).map { it.tableId })
    }

    @Test
    fun an_ack_marks_history_up_to_the_bound_and_leaves_later_rows_pending() = runTest {
        dao.insert(row(eventAt = 100L))
        dao.insert(row(eventAt = 200L))
        dao.insert(row(eventAt = 300L))

        val marked = dao.markSynced(eventAtUpTo = 200L)

        assertEquals(2, marked)
        assertEquals(2, dao.syncedCount())
        assertEquals(
            listOf(true, true, false),
            dao.recent(limit = 10).sortedBy { it.eventAt }.map { it.syncStatus },
        )
    }

    @Test
    fun marking_synced_twice_is_idempotent() = runTest {
        dao.insert(row(eventAt = 100L))

        assertEquals(1, dao.markSynced(eventAtUpTo = 100L))
        assertEquals(0, dao.markSynced(eventAtUpTo = 100L))
        assertEquals(1, dao.syncedCount())
    }

    @Test
    fun pruning_by_age_only_removes_synced_rows_older_than_the_window() = runTest {
        dao.insert(row(tableId = "lama-synced", eventAt = 10L))
        dao.insert(row(tableId = "baru-synced", eventAt = 900L))
        dao.markSynced(eventAtUpTo = 10L)
        dao.insert(row(tableId = "lama-pending", eventAt = 20L))
        dao.insert(row(tableId = "baru-pending", eventAt = 800L))

        val removed = dao.pruneSyncedBefore(cutoffMillis = 500L)

        assertEquals(1, removed)
        assertEquals(
            listOf("baru-pending", "lama-pending", "baru-synced"),
            dao.recent(limit = 10).map { it.tableId },
        )
    }

    @Test
    fun pruning_by_row_count_keeps_the_newest_rows_in_order() = runTest {
        repeat(12) { index -> dao.insert(row(tableId = "meja-$index", eventAt = index.toLong())) }

        val removed = dao.pruneOverflow(maxRows = 5)

        assertEquals(7, removed)
        assertEquals(5, dao.count())
        assertEquals(
            listOf("meja-11", "meja-10", "meja-9", "meja-8", "meja-7"),
            dao.recent(limit = 10).map { it.tableId },
        )
    }

    @Test
    fun clearing_history_leaves_nothing_behind() = runTest {
        repeat(3) { dao.insert(row()) }

        assertEquals(3, dao.clear())
        assertEquals(0, dao.count())
        assertTrue(dao.recent(limit = 10).isEmpty())
    }
}
