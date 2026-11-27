package id.warung.monitor.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import id.warung.monitor.core.database.MonitorDatabase
import id.warung.monitor.core.database.OutboxDao
import id.warung.monitor.core.database.OutboxEntity
import id.warung.monitor.core.model.SyncTableStatus
import id.warung.monitor.data.OutboxRepository
import id.warung.monitor.data.TableHistory
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
 * An ACK frees history for pruning, but only the history that belongs to the
 * acknowledged rows — and a history failure must never undo the ACK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboxSyncAdapterTest {

    private lateinit var database: MonitorDatabase
    private lateinit var dao: OutboxDao
    private lateinit var repository: OutboxRepository
    private lateinit var history: RecordingHistory

    private class RecordingHistory : TableHistory {
        val marks = mutableListOf<Long>()
        var failMarks = false

        override suspend fun record(tableId: String, status: SyncTableStatus, eventAtMillis: Long) =
            Unit

        override suspend fun markSyncedUpTo(eventAtMillis: Long): Int {
            if (failMarks) throw IllegalStateException("history locked")
            marks += eventAtMillis
            return 1
        }
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MonitorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.outbox()
        repository = OutboxRepository(dao, deviceId = { DEVICE }, clock = { 999L })
        history = RecordingHistory()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun row(createdAt: Long) {
        dao.insert(OutboxEntity(deviceId = DEVICE, payload = "{}", createdAt = createdAt))
    }

    @Test
    fun an_ack_releases_history_up_to_the_newest_acknowledged_row() = runTest {
        row(createdAt = 10L)
        row(createdAt = 20L)
        row(createdAt = 30L)
        val adapter = OutboxSyncAdapter(repository, history)

        assertEquals(2, adapter.acknowledge(ackedSeq = 2L))

        assertEquals(listOf(20L), history.marks)
        assertEquals(1, dao.pendingCount())
    }

    @Test
    fun an_ack_that_matches_nothing_leaves_history_untouched() = runTest {
        row(createdAt = 10L)
        val adapter = OutboxSyncAdapter(repository, history)

        assertEquals(0, adapter.acknowledge(ackedSeq = 0L))

        assertTrue(history.marks.isEmpty())
        assertEquals(1, dao.pendingCount())
    }

    @Test
    fun a_history_failure_does_not_undo_the_acknowledgement() = runTest {
        row(createdAt = 10L)
        row(createdAt = 20L)
        history.failMarks = true
        val adapter = OutboxSyncAdapter(repository, history)

        assertEquals(2, adapter.acknowledge(ackedSeq = 2L))

        assertEquals(0, dao.pendingCount())
        assertTrue(history.marks.isEmpty())
    }

    @Test
    fun pending_rows_are_exposed_with_their_sequence_for_the_coordinator() = runTest {
        row(createdAt = 10L)
        val adapter = OutboxSyncAdapter(repository, history)

        val pending = adapter.pending(limit = 10)

        assertEquals(listOf(1L), pending.map { it.seq })
        assertEquals("{}", pending.single().payload)
    }

    private companion object {
        const val DEVICE = "android-warung-01"
    }
}
