package id.warung.monitor.data

import id.warung.monitor.core.database.OutboxDao
import id.warung.monitor.core.database.OutboxEntity
import id.warung.monitor.core.model.SyncTableStatus
import id.warung.monitor.core.model.TableStatusJson
import id.warung.monitor.core.model.TableStatusPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The outbox must be durable before the UI is told, and must never drop an
 * unacknowledged event. This fake DAO keeps the semantics of the real one:
 * AUTOINCREMENT order, ACK by prefix, and no pruning of pending rows.
 */
class OutboxRepositoryTest {

    private class FakeOutboxDao : OutboxDao {
        val rows = mutableListOf<OutboxEntity>()
        private var next = 1L

        override suspend fun insert(row: OutboxEntity): Long {
            val seq = next++
            rows += row.copy(seq = seq)
            return seq
        }

        override suspend fun pending(limit: Int): List<OutboxEntity> =
            rows.filter { !it.isSynced }.sortedBy { it.seq }.take(limit)

        override suspend fun pendingCount(): Int = rows.count { !it.isSynced }

        override suspend fun pendingMaxSeq(): Long? =
            rows.filter { !it.isSynced }.maxOfOrNull { it.seq }

        override suspend fun markSynced(deviceId: String, ackedSeq: Long): Int {
            var affected = 0
            rows.replaceAll { row ->
                if (row.deviceId == deviceId && row.seq <= ackedSeq && !row.isSynced) {
                    affected += 1
                    row.copy(isSynced = true)
                } else {
                    row
                }
            }
            return affected
        }

        override suspend fun pruneSynced(cutoffMillis: Long): Int {
            val before = rows.size
            rows.removeAll { it.isSynced && it.createdAt < cutoffMillis }
            return before - rows.size
        }

        override suspend fun recordFailure(seq: Long, error: String) {
            rows.replaceAll {
                if (it.seq == seq) it.copy(attempts = it.attempts + 1, lastError = error) else it
            }
        }

        override suspend fun clear() = rows.clear()

        override suspend fun acknowledge(deviceId: String, ackedSeq: Long): Int =
            markSynced(deviceId, ackedSeq)
    }

    private fun payload(
        tableId: String = "meja-01",
        status: SyncTableStatus = SyncTableStatus.OCCUPIED,
        since: Long = 1_763_712_660_000L,
    ) = TableStatusPayload(tableId, status, since)

    @Test
    fun an_appended_event_is_stored_as_valid_json_before_anything_else() = runTest {
        val dao = FakeOutboxDao()
        val repository = OutboxRepository(dao, deviceId = { DEVICE }, clock = { 99L })

        val seq = repository.append(payload())

        assertEquals(1L, seq)
        val stored = dao.rows.single()
        assertEquals(DEVICE, stored.deviceId)
        assertEquals(99L, stored.createdAt)
        assertEquals(payload(), TableStatusJson.decode(stored.payload))
    }

    @Test
    fun unknown_is_never_converted_to_available() = runTest {
        val dao = FakeOutboxDao()
        val repository = OutboxRepository(dao, deviceId = { DEVICE }, clock = { 1L })

        repository.append(payload(status = SyncTableStatus.UNKNOWN))

        assertTrue(dao.rows.single().payload.contains("\"status\":\"unknown\""))
    }

    @Test
    fun the_device_id_is_resolved_once_and_reused() = runTest {
        val dao = FakeOutboxDao()
        var calls = 0
        val repository = OutboxRepository(
            dao,
            deviceId = { calls += 1; DEVICE },
            clock = { 1L },
        )

        repository.append(payload(tableId = "meja-01"))
        repository.append(payload(tableId = "meja-02"))

        assertEquals(1, calls)
        assertEquals(listOf(DEVICE, DEVICE), dao.rows.map { it.deviceId })
    }

    @Test
    fun pending_never_exceeds_the_contract_batch_limit() = runTest {
        val dao = FakeOutboxDao()
        val repository = OutboxRepository(dao, deviceId = { DEVICE }, clock = { 1L })
        repeat(150) { repository.append(payload(tableId = "meja-$it")) }

        val batch = repository.pending(limit = 500)

        assertEquals(100, batch.size)
        assertEquals(150, repository.pendingCount())
    }

    @Test
    fun acknowledge_only_clears_the_acked_prefix() = runTest {
        val dao = FakeOutboxDao()
        val repository = OutboxRepository(dao, deviceId = { DEVICE }, clock = { 1L })
        repeat(3) { repository.append(payload(tableId = "meja-$it")) }

        val acked = repository.acknowledge(ackedSeq = 2L)

        assertEquals(2, acked)
        assertEquals(1, repository.pendingCount())
        assertEquals(3L, repository.snapshot().newestSeq)
    }

    @Test
    fun pruning_keeps_an_event_that_was_never_acknowledged() = runTest {
        val dao = FakeOutboxDao()
        val repository = OutboxRepository(dao, deviceId = { DEVICE }, clock = { 1L })
        repository.append(payload())

        repository.prune(olderThanMillis = 0L)

        assertEquals(1, repository.pendingCount())
    }

    @Test
    fun an_invalid_payload_is_rejected_before_insert() = runTest {
        val dao = FakeOutboxDao()
        val repository = OutboxRepository(dao, deviceId = { DEVICE }, clock = { 1L })

        try {
            // `since` before the epoch cannot be represented on the wire.
            repository.append(payload(since = -1L))
            fail("negative timestamps must be rejected")
        } catch (error: IllegalArgumentException) {
            assertNotNull(error.message)
        }
        assertTrue(dao.rows.isEmpty())
    }

    private companion object {
        const val DEVICE = "android-warung-01"
    }
}
