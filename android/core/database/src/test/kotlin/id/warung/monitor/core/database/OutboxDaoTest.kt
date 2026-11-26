package id.warung.monitor.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The outbox is the only copy of an event until the server acknowledges it.
 * These tests lock the ordering, ACK, and retry rules that `docs/android-sync.md`
 * section 5 depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboxDaoTest {

    private lateinit var database: MonitorDatabase
    private lateinit var dao: OutboxDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MonitorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.outbox()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun row(
        payload: String = "{}",
        createdAt: Long = 1L,
        deviceId: String = DEVICE,
    ) = OutboxEntity(deviceId = deviceId, payload = payload, createdAt = createdAt)

    @Test
    fun sequence_starts_at_one_and_is_monotonic() = runTest {
        val first = dao.insert(row())
        val second = dao.insert(row())
        val third = dao.insert(row())

        assertEquals(1L, first)
        assertEquals(2L, second)
        assertEquals(3L, third)
    }

    @Test
    fun pending_rows_are_returned_oldest_first() = runTest {
        dao.insert(row(payload = "a"))
        dao.insert(row(payload = "b"))

        val pending = dao.pending(limit = 10)

        assertEquals(listOf(1L, 2L), pending.map { it.seq })
        assertEquals(listOf("a", "b"), pending.map { it.payload })
        assertEquals(2, dao.pendingCount())
        assertEquals(2L, dao.pendingMaxSeq())
    }

    @Test
    fun the_batch_limit_never_skips_the_oldest_event() = runTest {
        repeat(5) { dao.insert(row(payload = "e$it")) }

        val batch = dao.pending(limit = 2)

        assertEquals(listOf(1L, 2L), batch.map { it.seq })
        assertEquals(5, dao.pendingCount())
    }

    @Test
    fun an_ack_marks_everything_up_to_acked_seq_and_keeps_the_rest_pending() = runTest {
        repeat(4) { dao.insert(row()) }

        val affected = dao.acknowledge(DEVICE, ackedSeq = 2L)

        assertEquals(2, affected)
        assertEquals(listOf(3L, 4L), dao.pending(limit = 10).map { it.seq })
        assertEquals(4L, dao.pendingMaxSeq())
    }

    @Test
    fun acking_another_device_does_not_mark_this_devices_rows() = runTest {
        dao.insert(row(deviceId = DEVICE))

        val affected = dao.acknowledge("android-lain", ackedSeq = 99L)

        assertEquals(0, affected)
        assertEquals(1, dao.pendingCount())
    }

    @Test
    fun a_replayed_ack_is_idempotent() = runTest {
        repeat(2) { dao.insert(row()) }

        assertEquals(2, dao.acknowledge(DEVICE, ackedSeq = 2L))
        assertEquals(0, dao.acknowledge(DEVICE, ackedSeq = 2L))
        assertEquals(0, dao.pendingCount())
    }

    @Test
    fun a_failed_attempt_is_recorded_without_losing_the_event() = runTest {
        val seq = dao.insert(row())

        dao.recordFailure(seq, "timeout")
        dao.recordFailure(seq, "timeout")

        val stored = dao.pending(limit = 1).single()
        assertEquals(2, stored.attempts)
        assertEquals("timeout", stored.lastError)
        assertEquals(1, dao.pendingCount())
    }

    @Test
    fun pruning_never_deletes_an_unacked_row() = runTest {
        dao.insert(row(createdAt = 1L))
        dao.insert(row(createdAt = 2L))
        dao.acknowledge(DEVICE, ackedSeq = 1L)

        val removed = dao.pruneSynced(cutoffMillis = 100L)

        assertEquals(1, removed)
        assertEquals(1, dao.pendingCount())
        assertEquals(2L, dao.pending(limit = 1).single().seq)
    }

    @Test
    fun an_empty_outbox_reports_no_max_sequence() = runTest {
        assertNull(dao.pendingMaxSeq())
        assertEquals(0, dao.pendingCount())
        assertTrue(dao.pending(limit = 10).isEmpty())
    }

    private companion object {
        const val DEVICE = "android-warung-01"
    }
}
