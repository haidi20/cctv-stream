package id.warung.monitor.data

import id.warung.monitor.core.Engine
import id.warung.monitor.core.EngineEvent
import id.warung.monitor.core.EngineListener
import id.warung.monitor.di.EngineProvider
import id.warung.monitor.feature.home.domain.EngineChangeSignal
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Bridges Rust `Engine.subscribe` events to a coalesced Kotlin signal.
 *
 * The native listener is registered once per process and survives `stop`/
 * `start` (UniFFI keeps subscriptions across sessions), so switching cameras
 * does not leak listeners. Callbacks arrive on whichever thread drove the
 * engine: they only bump a [MutableStateFlow] revision and never touch the
 * engine, a database, or a log. The payload is intentionally discarded; consumers
 * re-read [id.warung.monitor.feature.home.domain.EngineStatusSource] so Rust
 * stays the only owner of the counts.
 *
 * A revision counter with [conflate] turns a burst of table events into one
 * pending refresh. Losing an intermediate event is safe: the next health
 * snapshot already contains every transition.
 */
@Singleton
class RustEngineEventSource(
    private val engine: () -> Engine?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EngineChangeSignal {

    @Inject
    constructor(engineProvider: EngineProvider) : this(
        engineProvider::engineOrNull,
        Dispatchers.IO,
    )

    private val revision = MutableStateFlow(0L)

    private val subscribed = AtomicBoolean(false)

    private val listener = object : EngineListener {
        override fun onEvent(event: EngineEvent) {
            revision.update { it + 1 }
        }
    }

    override fun changes(): Flow<Unit> = flow {
        // Creating the engine runs JNA's `Native.register`, which reads and maps
        // libcore_ffi.so. That must never happen on the main thread while Home
        // is drawing; the collector's first tick waits for it instead.
        withContext(ioDispatcher) { ensureSubscribed() }
        // Captured after subscription but before the baseline is consumed: a
        // transition that lands while Home reads the first snapshot still
        // differs from `seen` and therefore schedules exactly one more read.
        var seen = revision.value
        emit(Unit)
        while (true) {
            seen = revision.first { it != seen }
            emit(Unit)
        }
    }.conflate()

    /**
     * Best effort: a missing `libcore_ffi.so` leaves the flow silent and polling
     * stays the fallback. A failure resets the flag so a later collector retries;
     * [EngineProvider] may still cache a permanently missing library.
     */
    private fun ensureSubscribed() {
        if (!subscribed.compareAndSet(false, true)) return
        try {
            val target = engine()
            if (target == null) {
                subscribed.set(false)
                return
            }
            target.subscribe(listener)
        } catch (_: Throwable) {
            // Never let a native/loader failure escape into a collector: the
            // signal is optional, polling is the guaranteed path.
            subscribed.set(false)
        }
    }
}
