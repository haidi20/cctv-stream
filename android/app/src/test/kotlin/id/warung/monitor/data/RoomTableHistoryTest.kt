package id.warung.monitor.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import id.warung.monitor.core.database.MonitorDatabase
import id.warung.monitor.core.database.TableLogDao
import id.warung.monitor.core.model.SyncTableStatus
import id.warung.monitor.core.sync.RetentionPolicy
import id.warung.monitor.core.sync.RetentionWindow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The history store is the only thing allowed to delete `table_logs`, so its
 * ACK-before-prune rule is asserted against the real Room schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomTableHistoryTest {

    private lateinit var database: MonitorDatabase
    private lateinit var dao: TableLogDao
    private lateinit var history: RoomTableHistory
    private var databaseBytes = 0L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MonitorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.tableLogs()
        history = RoomTableHistory(dao, DatabaseSize { databaseBytes })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun a_recorded_transition_keeps_the_wire_status_and_wall_clock_time() = runTest {
        history.record("meja-1", SyncTableStatus.OCCUPIED, eventAtMillis = 1_000L)

        val row = dao.recent(limit = 1).single()
        assertEquals("meja-1", row.tableId)
        assertEquals("occupied", row.status)
        assertEquals(1_000L, row.eventAt)
        assertFalse("a new row is never synced", row.syncStatus)
    }

    @Test
    fun an_ack_releases_only_rows_recorded_before_the_bound() = runTest {
        history.record("meja-1", SyncTableStatus.OCCUPIED, eventAtMillis = 100L)
        history.record("meja-2", SyncTableStatus.AVAILABLE, eventAtMillis = 200L)
        history.record("meja-3", SyncTableStatus.UNKNOWN, eventAtMillis = 300L)

        assertEquals(2, history.markSyncedUpTo(200L))
        assertEquals(2, dao.syncedCount())
    }

    @Test
    fun pruning_keeps_unsent_rows_and_rows_inside_the_window() = runTest {
        val now = 30L * RetentionPolicy.DAY_MILLIS
        history.record("lama-terkirim", SyncTableStatus.OCCUPIED, eventAtMillis = 1L)
        history.markSyncedUpTo(1L)
        history.record("baru-terkirim", SyncTableStatus.OCCUPIED, eventAtMillis = now - 60_000L)
        history.markSyncedUpTo(now)
        history.record("belum-terkirim", SyncTableStatus.OCCUPIED, eventAtMillis = 2L)

        val removed = history.prune(nowMillis = now, window = RetentionWindow.THREE_DAYS)

        assertEquals(1, removed)
        assertEquals(
            listOf("belum-terkirim", "baru-terkirim"),
            dao.recent(limit = 10).map { it.tableId },
        )
    }

    @Test
    fun pruning_with_the_longest_window_keeps_everything_inside_it() = runTest {
        val now = 40L * RetentionPolicy.DAY_MILLIS
        history.record("dua-puluh-hari", SyncTableStatus.OCCUPIED, eventAtMillis = now - 20L * RetentionPolicy.DAY_MILLIS)
        history.markSyncedUpTo(now)

        assertEquals(0, history.prune(nowMillis = now, window = RetentionWindow.THIRTY_DAYS))
        assertEquals(1, dao.count())
    }

    @Test
    fun stats_report_committed_rows_and_the_database_size() = runTest {
        databaseBytes = 4096L
        history.record("meja-1", SyncTableStatus.AVAILABLE, eventAtMillis = 1L)
        history.record("meja-2", SyncTableStatus.AVAILABLE, eventAtMillis = 2L)

        val stats = history.stats()

        assertEquals(2, stats.rows)
        assertEquals(4096L, stats.databaseBytes)
        assertEquals(RetentionPolicy.MAX_ROWS, stats.rowLimit)
        assertTrue(stats.rows < stats.rowLimit)
    }
}
