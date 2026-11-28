package id.warung.monitor.engine

/**
 * The inference numbers this device has earned the right to use.
 *
 * A compiled-in default of 2 FPS at two ORT threads is a starting guess, not a
 * measurement. Once Rust reports a p95 latency, the next session can pick a
 * rate that actually fits the frame slot instead of repeating the same guess on
 * every install (`docs/kotlin-rust-gaps.md` G-14).
 *
 * The rate policy is one-directional: a measurement may only lower the rate a
 * profile already allows, never raise it. Otherwise a warm room could talk the
 * app into a cadence the profile ceiling exists to prevent.
 */
data class EngineTuning(
    val inferenceFps: Double,
    val intraThreads: UInt,
) {
    companion object {
        /**
         * Share of one sampling interval an inference may consume.
         *
         * Measured on an SM-A176B at 2 FPS (`docs/performance.md` section 6.3):
         * p50 192-204 ms, p95 296 ms against a 500 ms slot, with zero dropped
         * frames over a 30 minute soak. That is 59% of the slot. An 80% ceiling
         * (400 ms) still keeps that phone at 2 FPS, tolerates the +-10 ms
         * run-to-run spread, and leaves about 100 ms of every slot for capture,
         * decode, and the UI. A phone whose p95 crosses it drops one step.
         */
        const val SLOT_SHARE: Double = 0.8

        /**
         * Rates a measurement may choose between, fastest first.
         *
         * 2.0 is the balanced default, 1.5 the `lite` ceiling, and 1.0 the
         * lowest rate that still beats the 2 second debounce
         * (`docs/performance.md` section 8). Zero is absent on purpose: it means
         * "no inference at all" and belongs to the RAM floor alone.
         */
        val CANDIDATE_RATES: List<Double> = listOf(
            EngineStartPlanner.DEFAULT_INFERENCE_FPS,
            EngineStartPlanner.LITE_INFERENCE_FPS,
            EngineStartPlanner.LOW_RAM_INFERENCE_FPS,
        )

        /** The documented default, used before this device has measured anything. */
        val Default: EngineTuning = EngineTuning(
            inferenceFps = EngineStartPlanner.DEFAULT_INFERENCE_FPS,
            intraThreads = EngineStartPlanner.INTRA_THREADS,
        )

        /**
         * Tuning for a device whose p95 inference latency is [p95Ms].
         *
         * `null`, `0`, or a negative reading means "not measured" and keeps
         * [requestedFps]. An absent number is not evidence of a slow phone, the
         * same rule [EngineStartPlanner.inferenceFpsFor] applies to a missing
         * RAM reading.
         *
         * Threads are carried through, not derived. Two threads measured fastest
         * on SM-A176B (p50 192-204 ms, beating 3, 4, and 1), and no measurement
         * yet justifies a per-latency thread rule. Keeping the value in this type
         * means a stored thread count is honoured without inventing a policy.
         */
        fun of(
            p95Ms: Long?,
            requestedFps: Double = EngineStartPlanner.DEFAULT_INFERENCE_FPS,
            requestedThreads: UInt = EngineStartPlanner.INTRA_THREADS,
        ): EngineTuning {
            val measured = p95Ms?.takeIf { it > 0L }
                ?: return EngineTuning(requestedFps, requestedThreads)
            if (requestedFps <= 0.0) return EngineTuning(requestedFps, requestedThreads)

            val fits = CANDIDATE_RATES.firstOrNull { rate ->
                rate <= requestedFps && measured <= slotBudgetMs(rate)
            }
            // When even the slowest candidate is over budget the phone still
            // runs at that rate: 1 FPS is the floor, and stopping inference is
            // the RAM floor's decision, not a latency decision.
            val slowest = CANDIDATE_RATES.last()
            val chosen = fits ?: if (requestedFps >= slowest) slowest else requestedFps
            return EngineTuning(inferenceFps = chosen, intraThreads = requestedThreads)
        }

        /** Milliseconds one inference may spend inside a slot at [rate]. */
        fun slotBudgetMs(rate: Double): Double = (1_000.0 / rate) * SLOT_SHARE
    }
}
