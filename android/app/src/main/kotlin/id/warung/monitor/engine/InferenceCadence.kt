package id.warung.monitor.engine

import android.os.PowerManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses the pump interval from the phone's current condition.
 *
 * The documented operating profile is 2 FPS (`docs/performance.md` section 1),
 * dropping to 1 FPS only when the platform reports `SEVERE` heat or free RAM
 * below 150 MB (sections 5 and 6.1, `docs/monitoring.md` section 2.4). Those
 * are the two conditions a low-end phone actually hits while the user opens
 * another app, so they are the ones implemented instead of guessed thresholds.
 *
 * The decision is a pure function of its arguments: unit tests cover it on the
 * host, and the pump passes live readings from `PowerManager` and
 * `ActivityManager`.
 */
@Singleton
class InferenceCadence @Inject constructor() {

    /**
     * Delay before the next frame.
     *
     * @param thermalStatus `PowerManager.currentThermalStatus`, or `null` when
     *   the platform cannot report it. `null` is treated as "no evidence of
     *   heat", not as hot, so a device without the reading keeps 2 FPS.
     * @param availableRamMb `ActivityManager.MemoryInfo.availMem` in MB, or
     *   `null` when unknown. Below [LOW_RAM_MB] the rate halves.
     */
    fun delayMillis(
        thermalStatus: Int?,
        availableRamMb: Long?,
        requestedFps: Double = NORMAL_FPS,
    ): Long {
        val throttled = thermalStatus != null &&
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
            availableRamMb != null && availableRamMb < LOW_RAM_MB
        val rate = if (throttled) requestedFps / 2 else requestedFps
        return millisPerFrame(rate)
    }

    /**
     * Remaining wait for a tick that has already spent [workMs] working.
     *
     * The pump captures, packs, and infers inside the same loop. Sleeping the
     * full interval *after* that work makes the real rate
     * 1 / (interval + work); at 24 FPS a 25 ms inference would silently turn
     * 41 ms pacing into 66 ms (15 FPS). Subtracting the work keeps the loop at
     * the requested rate, and a slow tick (work >= interval) simply runs
     * back-to-back without piling up frames: the next capture waits for a
     * fresh one instead of queueing stale ones.
     *
     * A negative measurement is treated as zero so a clock glitch cannot
     * lengthen the interval.
     */
    fun paceDelayMillis(intervalMs: Long, workMs: Long): Long {
        val interval = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val work = workMs.coerceAtLeast(0L)
        return (interval - work).coerceAtLeast(0L)
    }

    /** One frame interval for [fps], never faster than [MAX_FPS] or slower than 0.5 FPS. */
    fun millisPerFrame(fps: Double): Long {
        val safe = if (fps.isFinite()) fps.coerceIn(MIN_FPS, MAX_FPS) else MIN_FPS
        return (1_000.0 / safe).toLong().coerceAtLeast(1L)
    }

    companion object {
        /** 2 FPS, the documented default for a visible or screen-off phone. */
        const val NORMAL_FPS: Double = 2.0

        /** Same default expressed as an interval, kept for existing callers. */
        const val NORMAL_DELAY_MS: Long = 500L

        /** 1 FPS under `SEVERE` heat or low free RAM, per docs/monitoring.md. */
        const val SLOW_DELAY_MS: Long = 1_000L

        /** Free-RAM threshold from docs/performance.md section 5. */
        const val LOW_RAM_MB: Long = 150L

        /** Benchmark ceiling from docs/add-feature.md "hard test"; matches core-ffi. */
        const val MAX_FPS: Double = 24.0

        /** Floor; a slower rate is indistinguishable from a dead sampler. */
        const val MIN_FPS: Double = 0.5

        /** Shortest interval any caller may pace at, the [MAX_FPS] period. */
        const val MIN_INTERVAL_MS: Long = 1_000L / 24
    }
}