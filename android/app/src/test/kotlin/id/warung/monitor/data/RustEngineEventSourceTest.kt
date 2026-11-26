package id.warung.monitor.data

import id.warung.monitor.core.Engine
import id.warung.monitor.core.EngineEvent
import id.warung.monitor.core.EngineHealth
import id.warung.monitor.core.EngineListener
import id.warung.monitor.core.NoHandle
import id.warung.monitor.core.StreamStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The event bridge must survive a missing library, subscribe exactly once, and
 * never turn a burst of table transitions into a burst of refreshes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RustEngineEventSourceTest {

    private class RecordingEngine : Engine(NoHandle) {
        var listener: EngineListener? = null
        var subscribes = 0

        override fun subscribe(listener: EngineListener) {
            subscribes += 1
            this.listener = listener
        }

        override fun health(): EngineHealth = throw UnsupportedOperationException()

        fun stream(status: StreamStatus) {
            listener?.onEvent(EngineEvent.Stream(status))
        }
    }

    @Test
    fun a_missing_library_still_delivers_one_baseline_and_does_not_throw() = runTest {
        val source = RustEngineEventSource({ null }, StandardTestDispatcher(testScheduler))

        // Home relies on this single tick to paint the first totals; after it
        // the flow stays silent and polling is the only path.
        val tick = withTimeout(1_000) { source.changes().first() }

        assertEquals(Unit, tick)
    }

    @Test
    fun a_throwing_engine_factory_is_contained_and_never_breaks_collection() = runTest {
        // A missing libcore_ffi.so surfaces as Throwable from EngineProvider.
        val source = RustEngineEventSource(
            { throw UnsatisfiedLinkError("libcore_ffi.so") },
            StandardTestDispatcher(testScheduler),
        )

        val tick = withTimeout(1_000) { source.changes().first() }

        assertEquals(Unit, tick)
    }

    @Test
    fun collecting_registers_the_native_listener_once_per_process() = runTest(UnconfinedTestDispatcher()) {
        val engine = RecordingEngine()
        val source = RustEngineEventSource({ engine }, StandardTestDispatcher(testScheduler))

        val first = launch { source.changes().collect { } }
        runCurrent()
        val second = launch { source.changes().collect { } }
        runCurrent()

        assertEquals("subscriptions survive stop/start", 1, engine.subscribes)
        first.cancel()
        second.cancel()
    }

    @Test
    fun a_baseline_tick_precedes_the_first_rust_event() = runTest(UnconfinedTestDispatcher()) {
        val engine = RecordingEngine()
        val source = RustEngineEventSource({ engine }, StandardTestDispatcher(testScheduler))
        var received = 0
        val collector = launch { source.changes().collect { received += 1 } }
        runCurrent()

        assertEquals("one baseline read so Home never waits a poll tick", 1, received)

        engine.stream(StreamStatus.CONNECTED)
        runCurrent()

        assertEquals("the event wakes the collector once", 2, received)
        collector.cancel()
    }

    @Test
    fun a_burst_while_the_consumer_is_busy_keeps_only_one_pending_refresh() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = RecordingEngine()
            val source = RustEngineEventSource({ engine }, StandardTestDispatcher(testScheduler))
            var received = 0
            val gate = CompletableDeferred<Unit>()
            // A busy consumer models a slow health read or a backgrounded UI.
            val collector = launch {
                source.changes().collect {
                    received += 1
                    if (received == 1) gate.await()
                }
            }
            runCurrent()
            assertEquals("baseline ticks once", 1, received)

            repeat(20) { engine.stream(StreamStatus.CONNECTED) }
            runCurrent()
            assertEquals("a busy consumer processes none of the burst", 1, received)

            gate.complete(Unit)
            runCurrent()

            assertEquals("baseline plus one coalesced refresh only", 2, received)
            collector.cancel()
        }
}
