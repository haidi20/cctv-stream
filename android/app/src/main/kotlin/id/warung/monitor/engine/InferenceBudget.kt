package id.warung.monitor.engine

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The inference rate the active session asked Rust to use.
 *
 * `Engine.start` owns the sampler inside Rust, but Kotlin still decides how
 * often it captures a texture. Those two rates must agree: when the session
 * runs at 1.5 FPS the pump may not capture at 2 FPS, and when a benchmark asks
 * for 24 FPS the pump must not stay pinned at 2 (which is what made the
 * "hard test" in docs/add-feature.md impossible).
 *
 * Process-wide because there is exactly one active session; the setter is
 * called from `RustCameraSession` before `Engine.start` and the pump reads it
 * on every tick.
 */
@Singleton
class InferenceBudget @Inject constructor() {

    private val requested = AtomicReference(DEFAULT_FPS)

    /** Rate the current session requested; the documented default before one starts. */
    fun requestedFps(): Double = requested.get()

    /** Publish the rate a session is about to use. Non-finite values are ignored. */
    fun setRequestedFps(fps: Double) {
        if (!fps.isFinite() || fps <= 0.0) return
        requested.set(fps.coerceIn(InferenceCadence.MIN_FPS, InferenceCadence.MAX_FPS))
    }

    /** Reset to the documented default when a session stops. */
    fun reset() {
        requested.set(DEFAULT_FPS)
    }

    companion object {
        /** Same 2 FPS default as `docs/performance.md` section 1. */
        const val DEFAULT_FPS: Double = 2.0
    }
}