package id.warung.monitor.sync

import id.warung.monitor.core.model.SyncAck
import id.warung.monitor.core.sync.SyncCoordinator
import id.warung.monitor.core.sync.SyncResult
import id.warung.monitor.core.sync.SyncTransport
import id.warung.monitor.core.database.OutboxDao
import id.warung.monitor.core.database.OutboxEntity
import id.warung.monitor.data.OutboxRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/monitoring.md` 4.3 fixes the retry ladder. WorkManager's own backoff
 * jumps from 10 s to 5 h, and a fresh unique request resets `runAttemptCount`,
 * so the ladder must come from a persisted [SyncAttemptStore].
 */
class SyncRunnerTest {

    private class FakeAttempts(private var count: Int = 0) : SyncAttemptStore {
        override suspend fun failureCount(): Int = count

        override suspend fun recordFailure(): Int {
            count += 1
            return count
        }

        override suspend fun reset() {
            count = 0
        }
    }

    private class FakeDao(private val rows: MutableList<OutboxEntity>) : OutboxDao {
        override suspend fun insert(row: OutboxEntity): Long = 0
        override suspend fun pending(limit: Int) = rows.take(limit)
        override suspend fun pendingCount() = rows.size
        override suspend fun pendingMaxSeq() = rows.maxOfOrNull { it.seq }

        override suspend fun markSynced(deviceId: String, ackedSeq: Long): Int {
            val before = rows.size
            rows.removeAll { it.deviceId == deviceId && it.seq <= ackedSeq }
            return before - rows.size
        }

        override suspend fun maxAckedCreatedAt(ackedSeq: Long): Long? =
            rows.filter { it.isSynced && it.seq <= ackedSeq }.maxOfOrNull { it.createdAt }

        override suspend fun pruneSynced(cutoffMillis: Long): Int {
            val before = rows.size
            rows.removeAll { it.isSynced && it.createdAt < cutoffMillis }
            return before - rows.size
        }

        override suspend fun recordFailure(seq: Long, error: String) = Unit
        override suspend fun clear() = rows.clear()
        override suspend fun acknowledge(deviceId: String, ackedSeq: Long) =
            markSynced(deviceId, ackedSeq)
    }

    private fun row(seq: Long) = OutboxEntity(
        seq = seq,
        deviceId = DEVICE,
        payload = "{}",
        createdAt = 1L,
    )

    private fun runner(
        rows: MutableList<OutboxEntity>,
        attempts: SyncAttemptStore = FakeAttempts(),
        transport: SyncTransport,
    ): SyncRunner = SyncRunner(
        coordinator = SyncCoordinator(
            outbox = object : SyncCoordinator.Outbox {
                private val dao = FakeDao(rows)
                private val repository = OutboxRepository(dao, { DEVICE }, { NOW })

                override suspend fun pending(limit: Int) =
                    repository.pending(limit).map { SyncCoordinator.PendingEvent(it.seq, it.payload) }

                override suspend fun acknowledge(ackedSeq: Long) = repository.acknowledge(ackedSeq)

                override suspend fun recordFailure(seq: Long, message: String) =
                    repository.recordFailure(seq, message)
            },
            transport = transport,
            clock = { NOW },
        ),
        outbox = OutboxRepository(FakeDao(rows), { DEVICE }, { NOW }),
        deviceId = { DEVICE },
        attempts = attempts,
    )

    @Test
    fun an_empty_outbox_is_done() = runTest {
        val rows = mutableListOf<OutboxEntity>()
        val runner = runner(rows) { SyncResult.Acked(SyncAck(DEVICE, 0, NOW, 0)) }

        assertEquals(SyncRunner.Decision.Done, runner.run())
        assertTrue(rows.isEmpty())
    }

    @Test
    fun progress_asks_for_another_bounded_run() = runTest {
        val rows = mutableListOf(row(1), row(2))
        val runner = runner(rows) { envelope ->
            SyncResult.Acked(SyncAck(DEVICE, envelope.seq, NOW, 0))
        }

        assertEquals(SyncRunner.Decision.RunAgain, runner.run())
        assertTrue(rows.isEmpty())
    }

    @Test
    fun the_retry_ladder_survives_re_enqueues_that_reset_workmanager_attempts() = runTest {
        val rows = mutableListOf(row(1))
        val attempts = FakeAttempts()
        val runner = runner(rows, attempts) { SyncResult.Retryable("offline") }

        assertEquals(SyncRunner.Decision.Retry(30_000L), runner.run())
        assertEquals(SyncRunner.Decision.Retry(60_000L), runner.run())
        assertEquals(SyncRunner.Decision.Retry(300_000L), runner.run())
        assertEquals(SyncRunner.Decision.Retry(900_000L), runner.run())
        assertEquals(SyncRunner.Decision.Retry(900_000L), runner.run())
    }

    @Test
    fun a_successful_drain_resets_the_persisted_failure_count() = runTest {
        val rows = mutableListOf(row(1))
        val attempts = FakeAttempts(count = 3)
        val runner = runner(rows, attempts) { SyncResult.Retryable("offline") }

        assertEquals(SyncRunner.Decision.Retry(900_000L), runner.run())

        rows.clear()
        val recovered = runner(rows, attempts) { SyncResult.Acked(SyncAck(DEVICE, 0, NOW, 0)) }
        assertEquals(SyncRunner.Decision.Done, recovered.run())
        assertEquals(0, attempts.failureCount())
    }

    @Test
    fun a_permanent_rejection_stops_instead_of_retrying_forever() = runTest {
        val rows = mutableListOf(row(1))
        val runner = runner(rows) { SyncResult.Rejected("unauthorized") }

        assertEquals(SyncRunner.Decision.Stop("unauthorized"), runner.run())
        assertEquals(1, rows.size)
    }

    private companion object {
        const val DEVICE = "android-warnet-01"
        const val NOW = 1_795_584_660_000L
    }
}

