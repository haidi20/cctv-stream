package id.warung.monitor.engine

import id.warung.monitor.core.EngineConfig
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.model.DetectionPreset
import id.warung.monitor.core.model.Redaction
import id.warung.monitor.core.model.TableConfigJson
import id.warung.monitor.feature.tables.data.StoredTableConfig

/**
 * Decides whether the Rust engine can start for one camera.
 *
 * Pure JVM: no Android types, no file system writes, no FFI. A `Skipped` or
 * `Unavailable` result is not a session failure; the map is still editable and
 * the totals stay an em dash until the engine really starts.
 */
class EngineStartPlanner(
    internal val inferenceFps: Double = DEFAULT_INFERENCE_FPS,
    private val debounceMs: ULong = DEFAULT_DEBOUNCE_MS,
    internal val intraThreads: UInt = INTRA_THREADS,
    internal val detectionPreset: DetectionPreset = DetectionPreset.DEFAULT,
) {

    /**
     * Same planner with the inference rate a device profile is allowed.
     *
     * A `lite` phone (3-4 GB RAM) is capped at 2 FPS, and a `blocked` profile
     * must not run inference at all, per `device-compatibility.md`. Returning a
     * new instance keeps the default constructor unchanged for existing tests.
     */
    fun withInferenceFps(fps: Double): EngineStartPlanner =
        EngineStartPlanner(
            inferenceFps = fps,
            debounceMs = debounceMs,
            intraThreads = intraThreads,
            detectionPreset = detectionPreset,
        )

    /**
     * Same planner with the numbers this device measured.
     *
     * [EngineTuning] carries both the rate and the ORT thread count, so a
     * measured value can replace the compiled-in guess without every caller
     * learning about latency (`docs/kotlin-rust-gaps.md` G-14).
     */
    fun withTuning(tuning: EngineTuning): EngineStartPlanner =
        EngineStartPlanner(
            inferenceFps = tuning.inferenceFps,
            debounceMs = debounceMs,
            intraThreads = tuning.intraThreads,
            detectionPreset = detectionPreset,
        )

    /**
     * Same planner with the confidence preset the user picked.
     *
     * The preset is stored per device, so it is applied here rather than per
     * camera. Only the confidence threshold moves; `min_box_px` and the IoU
     * threshold stay at their measured defaults until `docs/status.md` carries a
     * precision/recall table (`docs/detection-quality-todo.md` Q-05).
     */
    fun withDetectionPreset(preset: DetectionPreset): EngineStartPlanner =
        EngineStartPlanner(
            inferenceFps = inferenceFps,
            debounceMs = debounceMs,
            intraThreads = intraThreads,
            detectionPreset = preset,
        )

    fun plan(
        camera: CameraProfile,
        stored: StoredTableConfig?,
        modelPath: String?,
        runtimePath: String?,
    ): EngineStartPlan {
        if (camera.source is CameraSource.Demo) {
            return EngineStartPlan.Skipped(EngineStartSkip.Demo)
        }
        if (stored == null || stored.config.tables.isEmpty()) {
            return EngineStartPlan.Skipped(EngineStartSkip.NoTables)
        }
        val model = modelPath?.takeIf { it.isNotBlank() }
            ?: return EngineStartPlan.Unavailable(
                "Model ONNX belum tersedia",
                EngineStartBlocker.ModelMissing,
            )
        val runtime = runtimePath?.takeIf { it.isNotBlank() }
            ?: return EngineStartPlan.Unavailable(
                "ONNX Runtime belum tersedia",
                EngineStartBlocker.RuntimeMissing,
            )
        // A 2 GB device has no memory budget for ONNX Runtime at all; saying
        // "ready" and failing inside `Engine.start` would look like a crash.
        if (inferenceFps <= 0.0) {
            return EngineStartPlan.Unavailable(
                "RAM perangkat di bawah batas inferensi",
                EngineStartBlocker.RamTooLow,
            )
        }
        val json = try {
            TableConfigJson.encodeOrThrow(stored.config)
        } catch (error: IllegalArgumentException) {
            return EngineStartPlan.Unavailable(
                Redaction.message(error.message ?: "Konfigurasi meja tidak valid"),
                EngineStartBlocker.ConfigInvalid,
            )
        } catch (error: IllegalStateException) {
            return EngineStartPlan.Unavailable(
                Redaction.message(error.message ?: "Konfigurasi meja tidak valid"),
                EngineStartBlocker.ConfigInvalid,
            )
        }
        return EngineStartPlan.Ready(
            EngineConfig(
                cameraId = camera.id.value,
                modelPath = model,
                onnxRuntimePath = runtime,
                tablesJson = json,
                inferenceFps = inferenceFps,
                debounceMs = debounceMs,
                intraThreads = intraThreads,
                // Only confidence moves with the preset. IoU and the minimum
                // box size stay at the detector defaults (0.5 / 12 px) because
                // no measurement yet justifies changing them.
                confidenceThreshold = detectionPreset.confidenceThreshold,
                iouThreshold = null,
                minBoxPx = null,
            ),
        )
    }

    companion object {
        const val DEFAULT_INFERENCE_FPS: Double = 2.0
        const val DEFAULT_DEBOUNCE_MS: ULong = 2_000uL

        /**
         * ONNX Runtime session threads on a phone.
         *
         * Measured on an SM-A176B: two threads gave the lowest p50 latency
         * (192-204 ms) and beat three (208-256 ms) and four (229-238 ms) while
         * leaving more cores for decode and the UI. ONNX Runtime runs the
         * caller on the pool, so this is two threads total, one ORT worker.
         */
        const val INTRA_THREADS: UInt = 2u

        /** Inference rate for a `lite` phone: cheap, still faster than debounce. */
        const val LITE_INFERENCE_FPS: Double = 1.5

        /** Minimum total RAM for any inference profile, in MB. */
        const val MIN_RAM_MB: Long = 2_560L

        /** Total RAM at or below this is treated as a 2 GB device, in MB. */
        const val LOW_RAM_MB: Long = 3_072L

        /** `docs/performance.md` section 9: a 2 GB device runs at most 1 FPS. */
        const val LOW_RAM_INFERENCE_FPS: Double = 1.0

        /**
         * Inference rate allowed for a phone with [ramTotalMb] total memory.
         *
         * The 2 GB simulation answer from `docs/performance.md` section 9 is a
         * threshold, not a guess: no inference at all below [MIN_RAM_MB],
         * 1 FPS at or below [LOW_RAM_MB], otherwise the requested profile rate.
         * A missing reading keeps [requested] so a device that cannot report
         * RAM is not silently throttled by an invented number.
         */
        fun inferenceFpsFor(
            ramTotalMb: Long?,
            requested: Double = DEFAULT_INFERENCE_FPS,
        ): Double {
            val total = ramTotalMb ?: return requested
            if (total < MIN_RAM_MB) return 0.0
            if (total <= LOW_RAM_MB) return LOW_RAM_INFERENCE_FPS
            return requested
        }
    }
}

sealed interface EngineStartPlan {
    data class Ready(val config: EngineConfig) : EngineStartPlan
    data class Skipped(val reason: EngineStartSkip) : EngineStartPlan

    /**
     * A prerequisite is missing. [reason] stays a human sentence for logs and
     * old callers; [blocker] is the machine-readable cause the UI maps to its
     * own wording, so a message tweak here cannot silently change what Home
     * says (`docs/kotlin-rust-gaps.md` G-02).
     */
    data class Unavailable(
        val reason: String,
        val blocker: EngineStartBlocker = EngineStartBlocker.Unknown,
    ) : EngineStartPlan
}

enum class EngineStartSkip {
    Demo,
    NoTables,
}

/** Machine-readable cause behind [EngineStartPlan.Unavailable]. */
enum class EngineStartBlocker {
    ModelMissing,
    RuntimeMissing,
    RamTooLow,
    ConfigInvalid,

    /** A cause this enum does not name yet; the UI falls back to [reason]. */
    Unknown,
}
