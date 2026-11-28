package id.warung.monitor.data

import id.warung.monitor.core.Engine
import id.warung.monitor.core.EngineEvent
import id.warung.monitor.core.EngineHealth
import id.warung.monitor.core.EngineListener
import id.warung.monitor.core.NoHandle
import id.warung.monitor.core.StreamStatus
import id.warung.monitor.core.TableEvent
import id.warung.monitor.core.TableStatus
import id.warung.monitor.core.database.OutboxDao
import id.warung.monitor.core.database.OutboxEntity
import id.warung.monitor.core.model.SyncTableStatus
import id.warung.monitor.core.model.TableStatusJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A native transition must reach the outbox with wall-clock time, not the
 * monotonic `since_ms` the engine reports.
 */
class TableEventRecorderTest {

    private class RecordingDao : OutboxDao {
        val rows = mutableListOf<OutboxEntity>()
        private var next = 1L

        override suspend fun insert(row: OutboxEntity): Long {
            val seq = next++
            rows += row.copy(seq = seq)
            return seq
        }

        override suspend fun pending(limit: Int) = rows.take(limit)
        override suspend fun pendingCount() = rows.size
        override suspend fun pendingMaxSeq() = rows.maxOfOrNull { it.seq }
        override suspend fun markSynced(deviceId: String, ackedSeq: Long) = 0
        override suspend fun maxAckedCreatedAt(ackedSeq: Long): Long? = null
        override suspend fun pruneSynced(cutoffMillis: Long) = 0
        override suspend fun recordFailure(seq: Long, error: String) = Unit
        override suspend fun clear() = rows.clear()
        override suspend fun acknowledge(deviceId: String, ackedSeq: Long) = 0
    }

    private class RecordingEngine(
        private val health: EngineHealth,
        private val healthFailure: Throwable? = null,
    ) : Engine(NoHandle) {
        var listener: EngineListener? = null
        var subscribes = 0
        var healthCalls = 0

        override fun subscribe(listener: EngineListener) {
            subscribes += 1
            this.listener = listener
        }

        override fun health(): EngineHealth {
            healthCalls += 1
            healthFailure?.let { throw it }
            return health
        }

        fun table(
            id: String,
            status: TableStatus,
            sinceMs: ULong,
            confidence: Float = 0.9f,
            persons: UInt = 1u,
        ) {
            listener?.onEvent(
                EngineEvent.Table(
                    TableEvent(id, status, sinceMs, confidence, persons),
                ),
            )
        }
    }

    private fun health(uptimeMs: ULong) = EngineHealth(
        cameraId = "cam-1",
        stream = StreamStatus.CONNECTED,
        framesIn = 10uL,
        framesSampled = 5uL,
        connects = 1u,
        reconnectFailures = 0u,
        lastFrameAgeMs = 100uL,
        tables = 2u,
        tablesAvailable = 1u,
        tablesOccupied = 1u,
        tablesUnknown = 0u,
        uptimeMs = uptimeMs,
        inferenceFps = 2.0,
        lastInferenceAgeMs = 100uL,
        frameToInferenceMs = 0uL,
        rssKb = 0uL,
        framesDropped = 0uL,
        inferenceP50Ms = 0uL,
        inferenceP95Ms = 0uL,
        needsRecalibration = false,
        viewMoved = false,
        viewChangedFraction = 0f,
        modelReady = true,
    )

    @Test
    fun a_table_transition_becomes_an_outbox_row_with_wall_clock_time() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        // The session began 30 s ago, and the transition became stable 10 s in.
        val engine = RecordingEngine(health(uptimeMs = 30_000uL))
        val recorder = TableEventRecorder(
            engine = { engine },
            outbox = repository,
            clock = { NOW },
            scope = this,
        )
        recorder.start()

        engine.table("meja-01", TableStatus.OCCUPIED, sinceMs = 10_000uL)
        recorder.awaitIdle()

        val stored = TableStatusJson.decode(dao.rows.single().payload)
        assertEquals("meja-01", stored.tableId)
        assertEquals(SyncTableStatus.OCCUPIED, stored.status)
        assertEquals(NOW - 30_000L + 10_000L, stored.sinceEpochMs)
    }

    @Test
    fun the_listener_is_registered_only_once() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder({ engine }, repository, { NOW }, this)

        recorder.start()
        recorder.start()

        assertEquals(1, engine.subscribes)
    }

    @Test
    fun stream_events_never_create_table_rows() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder({ engine }, repository, { NOW }, this)
        recorder.start()

        engine.listener?.onEvent(EngineEvent.Stream(StreamStatus.CONNECTED))
        recorder.awaitIdle()

        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun unknown_stays_unknown_in_the_queued_payload() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder({ engine }, repository, { NOW }, this)
        recorder.start()

        engine.table("meja-02", TableStatus.UNKNOWN, sinceMs = 500uL, persons = 0u)
        recorder.awaitIdle()

        val stored = TableStatusJson.decode(dao.rows.single().payload)
        assertEquals(SyncTableStatus.UNKNOWN, stored.status)
    }

    @Test
    fun the_same_transition_delivered_twice_is_queued_once() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder({ engine }, repository, { NOW }, this)
        recorder.start()

        engine.table("meja-01", TableStatus.OCCUPIED, sinceMs = 500uL)
        engine.table("meja-01", TableStatus.OCCUPIED, sinceMs = 500uL)
        recorder.awaitIdle()

        assertEquals(1, dao.rows.size)
    }

    @Test
    fun two_tables_with_the_same_status_are_both_queued() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder({ engine }, repository, { NOW }, this)
        recorder.start()

        engine.table("meja-01", TableStatus.OCCUPIED, sinceMs = 500uL)
        engine.table("meja-02", TableStatus.OCCUPIED, sinceMs = 600uL)
        recorder.awaitIdle()

        assertEquals(2, dao.rows.size)
    }

    @Test
    fun transitions_are_stored_in_delivery_order() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder({ engine }, repository, { NOW }, this)
        recorder.start()

        val delivered = (0 until 20).map { index -> "meja-%02d".format(index) }
        delivered.forEach { tableId ->
            engine.table(tableId, TableStatus.OCCUPIED, sinceMs = 500uL)
        }
        recorder.awaitIdle()

        // The seq assigned by the DAO must follow callback order even though
        // the writes run on a thread pool.
        val stored = dao.rows.sortedBy { it.seq }.map { TableStatusJson.decode(it.payload).tableId }
        assertEquals(delivered, stored)
    }

    @Test
    fun the_health_snapshot_never_blocks_the_native_callback() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder({ engine }, repository, { NOW }, this)
        recorder.start()

        engine.table("meja-01", TableStatus.OCCUPIED, sinceMs = 500uL)

        // `runTest` is still on its first yield point: the callback must not
        // have called into the engine synchronously.
        assertEquals(0, engine.healthCalls)
        recorder.awaitIdle()
        assertEquals(1, engine.healthCalls)
    }

    @Test
    fun a_failed_health_snapshot_never_stamps_a_future_time() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val engine = RecordingEngine(
            health = health(0uL),
            healthFailure = IllegalStateException("engine stopped"),
        )
        val recorder = TableEventRecorder({ engine }, repository, { NOW }, this)
        recorder.start()

        engine.table("meja-01", TableStatus.OCCUPIED, sinceMs = 5_000uL)
        recorder.awaitIdle()

        // Without a session anchor the event is stamped no later than "now"
        // instead of "now + since_ms", which would be in the future.
        val stored = TableStatusJson.decode(dao.rows.single().payload)
        assertEquals(NOW, stored.sinceEpochMs)
    }

    @Test
    fun a_missing_library_does_not_throw_and_keeps_sync_off() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val recorder = TableEventRecorder(
            engine = { throw UnsatisfiedLinkError("libcore_ffi.so") },
            outbox = repository,
            clock = { NOW },
            scope = this,
        )

        recorder.start()
        recorder.awaitIdle()

        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun sync_is_requested_only_after_a_row_is_durable() = runTest {
        val dao = RecordingDao()
        val durableAtTrigger = mutableListOf<Int>()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder(
            engine = { engine },
            outbox = repository,
            clock = { NOW },
            scope = this,
            onRecorded = { durableAtTrigger += dao.rows.size },
        )
        recorder.start()

        engine.table("meja-01", TableStatus.OCCUPIED, sinceMs = 500uL)
        engine.table("meja-01", TableStatus.OCCUPIED, sinceMs = 500uL)
        recorder.awaitIdle()

        // One transition, and the trigger saw the row already stored.
        assertEquals(listOf(1), durableAtTrigger)
    }

    @Test
    fun a_transition_that_arrives_only_from_push_frame_is_still_queued() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        // No `start()`: the listener never subscribed, which is exactly the
        // failure this second channel exists for.
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder({ engine }, repository, { NOW }, this)

        recorder.recordObserved(
            listOf(TableEvent("meja-01", TableStatus.OCCUPIED, 500uL, 0.9f, 1u)),
        )
        recorder.awaitIdle()

        assertEquals("a frame-returned transition must not be lost", 1, dao.rows.size)
        assertEquals(0, engine.subscribes)
    }

    @Test
    fun the_listener_and_the_push_frame_return_value_collapse_into_one_row() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder({ engine }, repository, { NOW }, this)
        recorder.start()

        // The same transition on both channels. Each derives its own wall-clock
        // anchor, so only Rust's `since_ms` can identify it as one event.
        engine.table("meja-01", TableStatus.OCCUPIED, sinceMs = 500uL)
        recorder.recordObserved(
            listOf(TableEvent("meja-01", TableStatus.OCCUPIED, 500uL, 0.9f, 1u)),
        )
        recorder.awaitIdle()

        assertEquals(1, dao.rows.size)
    }

    @Test
    fun two_different_push_frame_transitions_are_two_rows() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder({ engine }, repository, { NOW }, this)

        recorder.recordObserved(
            listOf(
                TableEvent("meja-01", TableStatus.OCCUPIED, 500uL, 0.9f, 1u),
                TableEvent("meja-01", TableStatus.AVAILABLE, 1_500uL, 0.9f, 0u),
            ),
        )
        recorder.awaitIdle()

        assertEquals(2, dao.rows.size)
    }

    @Test
    fun a_failed_write_does_not_request_sync() = runTest {
        val failingDao = object : OutboxDao {
            override suspend fun insert(row: OutboxEntity): Long =
                throw IllegalStateException("disk full")

            override suspend fun pending(limit: Int) = emptyList<OutboxEntity>()
            override suspend fun pendingCount() = 0
            override suspend fun pendingMaxSeq(): Long? = null
            override suspend fun markSynced(deviceId: String, ackedSeq: Long) = 0
            override suspend fun maxAckedCreatedAt(ackedSeq: Long): Long? = null
            override suspend fun pruneSynced(cutoffMillis: Long) = 0
            override suspend fun recordFailure(seq: Long, error: String) = Unit
            override suspend fun clear() = Unit
            override suspend fun acknowledge(deviceId: String, ackedSeq: Long) = 0
        }
        val repository = OutboxRepository(failingDao, deviceId = { "android-test" }, clock = { NOW })
        var triggered = 0
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder(
            engine = { engine },
            outbox = repository,
            clock = { NOW },
            scope = this,
            onRecorded = { triggered += 1 },
        )
        recorder.start()

        engine.table("meja-01", TableStatus.OCCUPIED, sinceMs = 500uL)
        recorder.awaitIdle()

        assertEquals(0, triggered)
        assertEquals("a lost transition must be counted", 1, recorder.lostEvents())
    }

    @Test
    fun a_transition_is_also_appended_to_the_prunable_history() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        val recorded = mutableListOf<Pair<String, SyncTableStatus>>()
        val history = object : TableHistory {
            override suspend fun record(
                tableId: String,
                status: SyncTableStatus,
                eventAtMillis: Long,
            ) {
                recorded += tableId to status
            }

            override suspend fun markSyncedUpTo(eventAtMillis: Long): Int = 0
        }
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder(
            engine = { engine },
            outbox = repository,
            clock = { NOW },
            scope = this,
            history = history,
        )
        recorder.start()

        engine.table("meja-01", TableStatus.OCCUPIED, sinceMs = 100uL)
        recorder.awaitIdle()

        assertEquals(listOf("meja-01" to SyncTableStatus.OCCUPIED), recorded)
    }

    @Test
    fun a_history_failure_still_leaves_the_event_in_the_outbox() = runTest {
        val dao = RecordingDao()
        val repository = OutboxRepository(dao, deviceId = { "android-test" }, clock = { NOW })
        var triggered = 0
        val history = object : TableHistory {
            override suspend fun record(
                tableId: String,
                status: SyncTableStatus,
                eventAtMillis: Long,
            ) = throw IllegalStateException("history locked")

            override suspend fun markSyncedUpTo(eventAtMillis: Long): Int = 0
        }
        val engine = RecordingEngine(health(1_000uL))
        val recorder = TableEventRecorder(
            engine = { engine },
            outbox = repository,
            clock = { NOW },
            scope = this,
            onRecorded = { triggered += 1 },
            history = history,
        )
        recorder.start()

        engine.table("meja-02", TableStatus.AVAILABLE, sinceMs = 100uL)
        recorder.awaitIdle()

        assertEquals(1, dao.rows.size)
        assertEquals(1, triggered)
    }

    private companion object {
        const val NOW = 1_763_712_660_000L
    }
}
