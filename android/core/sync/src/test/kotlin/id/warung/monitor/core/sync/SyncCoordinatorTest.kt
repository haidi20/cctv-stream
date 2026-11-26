package id.warung.monitor.core.sync

import id.warung.monitor.core.model.SyncAck
import id.warung.monitor.core.model.SyncRequestEnvelope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator is the only place that decides when an event may disappear.
 * Every test here maps to a rule in `docs/android-sync.md` sections 5 and 8.
 */
class SyncCoordinatorTest {

    private class FakeOutbox(rows: List<SyncCoordinator.PendingEvent>) : SyncCoordinator.Outbox {
        val rows = rows.toMutableList()
        val failures = mutableListOf<Pair<Long, String>>()

        override suspend fun pending(limit: Int) = rows.take(limit)

        override suspend fun acknowledge(ackedSeq: Long): Int {
            val before = rows.size
            rows.removeAll { it.seq <= ackedSeq }
            return before - rows.size
        }

        override suspend fun recordFailure(seq: Long, message: String) {
            failures += seq to message
        }
    }

    private fun rows(count: Int, startAt: Long = 1L) =
        (0 until count).map { SyncCoordinator.PendingEvent(startAt + it, """{"n":${startAt + it}}""") }

    private fun ack(seq: Long) = SyncAck(
        deviceId = DEVICE,
        ackedSeq = seq,
        serverTimeEpochMs = NOW,
        duplicates = 0,
    )

    @Test
    fun an_empty_outbox_does_not_call_the_transport() = runTest {
        var calls = 0
        val coordinator = SyncCoordinator(
            outbox = FakeOutbox(emptyList()),
            transport = { calls += 1; SyncResult.Acked(ack(0)) },
            clock = { NOW },
        )

        assertEquals(SyncCoordinator.Outcome.Empty, coordinator.syncAll(deviceId = DEVICE))
        assertEquals(0, calls)
    }

    @Test
    fun an_ack_removes_only_the_acknowledged_prefix() = runTest {
        val outbox = FakeOutbox(rows(5))
        val sentSeqs = mutableListOf<Long>()
        val coordinator = SyncCoordinator(
            outbox = outbox,
            transport = { envelope ->
                sentSeqs += envelope.seq
                // The server accepts the first three, then the rest.
                SyncResult.Acked(ack(if (sentSeqs.size == 1) 3 else envelope.seq))
            },
            clock = { NOW },
        )

        val outcome = coordinator.syncAll(deviceId = DEVICE)

        assertEquals(5, (outcome as SyncCoordinator.Outcome.Progress).acked)
        assertTrue(outbox.rows.isEmpty())
        // `seq` is the highest event in the batch: the server's `last_seq`.
        assertEquals(5L, sentSeqs.first())
    }

    @Test
    fun the_envelope_carries_at_most_one_hundred_events() = runTest {
        val outbox = FakeOutbox(rows(250))
        val sizes = mutableListOf<Int>()
        val coordinator = SyncCoordinator(
            outbox = outbox,
            transport = { envelope ->
                sizes += envelope.events.size
                SyncResult.Acked(ack(envelope.seq))
            },
            clock = { NOW },
        )

        coordinator.syncAll(deviceId = DEVICE)

        assertTrue(sizes.all { it <= SyncRequestEnvelope.MAX_EVENTS })
        assertEquals(100, sizes.first())
        assertTrue(outbox.rows.isEmpty())
    }

    @Test
    fun a_retryable_failure_keeps_rows_and_returns_the_backoff_delay() = runTest {
        val outbox = FakeOutbox(rows(3))
        val coordinator = SyncCoordinator(
            outbox = outbox,
            transport = { SyncResult.Retryable("offline") },
            clock = { NOW },
        )

        val outcome = coordinator.syncAll(failureCount = 1, deviceId = DEVICE) as SyncCoordinator.Outcome.Retry

        assertEquals(60_000L, outcome.delayMillis)
        assertEquals(3, outbox.rows.size)
        assertEquals(1L to "offline", outbox.failures.single())
    }

    @Test
    fun a_throwing_transport_is_treated_as_offline() = runTest {
        val outbox = FakeOutbox(rows(2))
        val coordinator = SyncCoordinator(
            outbox = outbox,
            transport = { throw java.io.IOException("socket reset") },
            clock = { NOW },
        )

        val outcome = coordinator.syncAll(deviceId = DEVICE) as SyncCoordinator.Outcome.Retry

        assertEquals("transport_error", outcome.reason)
        assertEquals(2, outbox.rows.size)
    }

    @Test
    fun a_server_rejection_blocks_the_queue_instead_of_dropping_events() = runTest {
        val outbox = FakeOutbox(rows(2))
        val coordinator = SyncCoordinator(
            outbox = outbox,
            transport = { SyncResult.Rejected("unauthorized") },
            clock = { NOW },
        )

        val outcome = coordinator.syncAll(deviceId = DEVICE)

        assertEquals(SyncCoordinator.Outcome.Blocked("unauthorized"), outcome)
        assertEquals(2, outbox.rows.size)
    }

    @Test
    fun a_lost_ack_is_replayed_once_and_then_resolved() = runTest {
        val outbox = FakeOutbox(rows(3))
        var attempts = 0
        val coordinator = SyncCoordinator(
            outbox = outbox,
            transport = { envelope ->
                attempts += 1
                if (attempts == 1) {
                    SyncResult.SequenceGap(expectedSeq = 1)
                } else {
                    SyncResult.Acked(ack(envelope.seq))
                }
            },
            clock = { NOW },
        )

        val outcome = coordinator.syncAll(deviceId = DEVICE)

        assertEquals(2, attempts)
        assertEquals(3, (outcome as SyncCoordinator.Outcome.Progress).acked)
        assertTrue(outbox.rows.isEmpty())
    }

    @Test
    fun a_gap_the_device_cannot_fill_needs_a_snapshot_and_stops() = runTest {
        val outbox = FakeOutbox(rows(3, startAt = 10))
        val coordinator = SyncCoordinator(
            outbox = outbox,
            transport = { SyncResult.SequenceGap(expectedSeq = 1) },
            clock = { NOW },
        )

        val outcome = coordinator.syncAll(deviceId = DEVICE)

        assertEquals(SyncCoordinator.Outcome.Blocked("seq_gap_needs_snapshot"), outcome)
        assertEquals(3, outbox.rows.size)
    }

    @Test
    fun an_oversized_payload_is_split_instead_of_resent_unchanged() = runTest {
        val outbox = FakeOutbox(rows(400))
        val sizes = mutableListOf<Int>()
        val coordinator = SyncCoordinator(
            outbox = outbox,
            transport = { envelope ->
                sizes += envelope.events.size
                if (sizes.size == 1) {
                    SyncResult.PayloadTooLarge
                } else {
                    SyncResult.Acked(ack(envelope.seq))
                }
            },
            clock = { NOW },
        )

        coordinator.syncAll(deviceId = DEVICE)

        assertEquals(100, sizes.first())
        assertTrue(sizes.drop(1).all { it == 50 })
        assertTrue(outbox.rows.isEmpty())
    }

    @Test
    fun one_run_is_bounded_even_if_every_batch_succeeds() = runTest {
        val outbox = FakeOutbox(rows(2_500))
        var calls = 0
        val coordinator = SyncCoordinator(
            outbox = outbox,
            transport = { envelope -> calls += 1; SyncResult.Acked(ack(envelope.seq)) },
            clock = { NOW },
        )

        val outcome = coordinator.syncAll(deviceId = DEVICE)

        assertEquals(SyncCoordinator.DEFAULT_MAX_BATCHES, calls)
        assertEquals(2000, (outcome as SyncCoordinator.Outcome.Progress).acked)
        assertEquals(500, outbox.rows.size)
    }

    private companion object {
        const val DEVICE = "android-warnet-01"
        const val NOW = 1_795_584_660_000L
    }
}

