package id.warung.monitor.data

import id.warung.monitor.core.Engine
import id.warung.monitor.core.EngineEvent
import id.warung.monitor.core.EngineListener
import id.warung.monitor.core.TableEvent
import id.warung.monitor.core.TableStatus
import id.warung.monitor.core.model.SyncTableStatus
import id.warung.monitor.core.model.TableStatusPayload
import id.warung.monitor.di.EngineProvider
import id.warung.monitor.sync.SyncTrigger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Turns native table transitions into durable outbox rows.
 *
 * The listener runs on whichever thread drove the engine and, per the
 * `core-ffi` contract, must not call back into the engine. It therefore only
 * timestamps the event and enqueues it; the health snapshot and the Room write
 * happen later on an owned background scope. Writes are chained through
 * [serialTail] so `seq` always matches delivery order even though several
 * coroutines share a thread pool. A listener failure cannot stall the engine
 * because everything is caught except cancellation.
 *
 * `since_ms` is monotonic **since engine start**, not wall-clock time
 * (`docs/data-contracts.md` section 2). The anchor is derived from a health
 * snapshot taken after the event: `now - uptime_ms` is the session start, so a
 * late event still gets the right wall-clock time even if the listener was
 * delayed. `uptime_ms` resets on every `start`, so events from a previous camera
 * cannot be stamped with the new session's clock.
 *
 * A prunable `table_logs` row is appended after the outbox write, so local
 * history survives restarts without ever being the only copy of an event.
 */
@Singleton
class TableEventRecorder(
    private val engine: () -> Engine?,
    private val outbox: OutboxRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onRecorded: () -> Unit = {},
    private val history: TableHistory = TableHistory.None,
) {

    @Inject
    constructor(
        engineProvider: EngineProvider,
        outbox: OutboxRepository,
        syncTrigger: SyncTrigger,
        history: RoomTableHistory,
    ) : this(
        engine = engineProvider::engineOrNull,
        outbox = outbox,
        onRecorded = syncTrigger::requestSync,
        history = history,
    )

    private val subscribed = AtomicBoolean(false)
    private val writesInFlight = AtomicLong(0L)
    private val lostEventCount = AtomicLong(0L)
    private val lastRecorded = ConcurrentHashMap<String, RecordedState>()
    private val serialTail = AtomicReference(completedDeferred())

    /** Number of transitions that could not be made durable in this process. */
    fun lostEvents(): Long = lostEventCount.get()

    /**
     * Queues transitions that `Engine.pushFrame` returned.
     *
     * The listener normally reports the same transitions first. Rust's
     * monotonic `since_ms` is the identity of a transition, so the duplicate
     * collapses in [shouldRecord] instead of reaching Room twice. When the
     * listener never subscribed (missing library, a `subscribe` that threw),
     * this is the only path that still records the event.
     */
    fun recordObserved(events: List<TableEvent>) {
        if (events.isEmpty()) return
        val observedAt = clock()
        for (event in events) record(event, observedAt)
    }

    private val listener = object : EngineListener {
        override fun onEvent(event: EngineEvent) {
            // Only table transitions are queued; `stream` events drive the UI
            // and would otherwise duplicate rows on every reconnect.
            if (event !is EngineEvent.Table) return
            record(event.event, clock())
        }
    }

    /** Registers the native listener once; safe to call from app startup. */
    fun start() {
        if (!subscribed.compareAndSet(false, true)) return
        try {
            val target = engine() ?: run {
                subscribed.set(false)
                return
            }
            target.subscribe(listener)
        } catch (_: Throwable) {
            // Missing library or a failed factory: sync stays off, inference
            // and UI are unaffected.
            subscribed.set(false)
        }
    }

    /** Completes when every queued write has finished; used by tests and shutdown. */
    suspend fun awaitIdle() {
        while (writesInFlight.get() > 0L) {
            kotlinx.coroutines.delay(1)
        }
    }

    private fun record(event: TableEvent, observedAt: Long) {
        writesInFlight.incrementAndGet()
        val done = CompletableDeferred<Unit>()
        var previous: CompletableDeferred<Unit>
        do {
            previous = serialTail.get()
        } while (!serialTail.compareAndSet(previous, done))
        val write = scope.launch {
            try {
                previous.await()
                // Health and dedupe run here, never on the FFI callback
                // thread. `previous.await()` makes this section serial, so
                // dedupe order matches delivery order.
                val payload = try {
                    toPayload(event, observedAt)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return@launch
                }
                if (!shouldRecord(event)) return@launch
                outbox.append(payload)
                // History is local and prunable, so a failure here must never
                // cost the event: the outbox row above is already durable.
                try {
                    history.record(payload.tableId, payload.status, observedAt)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Best-effort only; the outbox remains the source of truth.
                }
                // Sync is optional; the callback is a no-op until a server is
                // configured. It runs only after the row is durable.
                onRecorded()
            } catch (error: CancellationException) {
                // Scope cancellation ends this recorder; the event is not
                // durable, but there is no owner left to retry it.
            } catch (_: Throwable) {
                // The event is lost, but the stream must keep running. A
                // persistent failure is visible as an empty outbox, not as a
                // crashed inference thread.
                lostEventCount.incrementAndGet()
            } finally {
                done.complete(Unit)
            }
        }
        // Covers a scope cancelled before the body ran: the next writer must
        // not wait on a `done` that will never complete.
        write.invokeOnCompletion {
            done.complete(Unit)
            writesInFlight.decrementAndGet()
        }
    }

    /**
     * Suppresses a duplicate that arrives before the first write lands.
     *
     * Three things describe one transition: the table, the status, and Rust's
     * monotonic `since_ms`. Keying on `since_ms` rather than the derived epoch
     * is what makes the listener and the `pushFrame` return value collapse into
     * a single row: each path derives its own wall-clock anchor, so the epochs
     * differ by a few milliseconds while `since_ms` is identical.
     */
    private fun shouldRecord(event: TableEvent): Boolean {
        val next = RecordedState(event.status.toSyncStatus(), event.sinceMs)
        return lastRecorded.put(event.tableId, next) != next
    }

    private fun toPayload(event: TableEvent, now: Long): TableStatusPayload {
        // Snapshot health here, off the listener thread. `now - uptime` is the
        // session anchor and stays constant while the session runs, so taking
        // it a moment after the callback does not skew `since`.
        //
        // This read is deliberately *not* served by the 1 Hz UI snapshot cache
        // (`RustEngineStatusSource`): `since_ms` is derived from `uptime_ms`,
        // and a snapshot up to a second old would place the transition in the
        // past and corrupt turnover durations. Transitions are rare, so this
        // costs one lock acquisition per real change, not one per poll.
        val health = try {
            engine()?.health()
        } catch (_: Throwable) {
            null
        }
        val uptime = health?.uptimeMs?.toLong() ?: 0L
        val sessionAnchor = now - uptime
        // A missing or stale health snapshot must never place a transition in
        // the future, which would corrupt turnover durations on the server.
        val sinceEpochMs = (sessionAnchor + event.sinceMs.toLong()).coerceIn(0L, now)
        return TableStatusPayload(
            tableId = event.tableId,
            status = event.status.toSyncStatus(),
            sinceEpochMs = sinceEpochMs,
            confidence = event.confidence.toDouble().takeIf { it in 0.0..1.0 },
            personsInRegion = event.personsInRegion.toLong(),
        )
    }

    /** Exhaustive: a new native status must be classified here, not guessed. */
    private fun TableStatus.toSyncStatus(): SyncTableStatus = when (this) {
        TableStatus.AVAILABLE -> SyncTableStatus.AVAILABLE
        TableStatus.OCCUPIED -> SyncTableStatus.OCCUPIED
        TableStatus.UNKNOWN -> SyncTableStatus.UNKNOWN
    }

    private data class RecordedState(
        val status: SyncTableStatus,
        /** Rust's monotonic transition stamp; stable across delivery paths. */
        val sinceMs: ULong,
    )

    private companion object {
        fun completedDeferred(): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { it.complete(Unit) }
    }
}
