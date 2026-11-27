package id.warung.monitor.engine

import id.warung.monitor.core.EngineConfig
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
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
) {

    /**
     * Same planner with the inference rate a device profile is allowed.
     *
     * A `lite` phone (3-4 GB RAM) is capped at 2 FPS, and a `blocked` profile
     * must not run inference at all, per `device-compatibility.md`. Returning a
     * new instance keeps the default constructor unchanged for existing tests.
     */
    fun withInferenceFps(fps: Double): EngineStartPlanner =
        EngineStartPlanner(inferenceFps = fps, debounceMs = debounceMs)

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
            ?: return EngineStartPlan.Unavailable("Model ONNX belum tersedia")
        val runtime = runtimePath?.takeIf { it.isNotBlank() }
            ?: return EngineStartPlan.Unavailable("ONNX Runtime belum tersedia")
        // A 2 GB device has no memory budget for ONNX Runtime at all; saying
        // "ready" and failing inside `Engine.start` would look like a crash.
        if (inferenceFps <= 0.0) {
            return EngineStartPlan.Unavailable("RAM perangkat di bawah batas inferensi")
        }
        val json = try {
            TableConfigJson.encodeOrThrow(stored.config)
        } catch (error: IllegalArgumentException) {
            return EngineStartPlan.Unavailable(Redaction.message(error.message ?: "Konfigurasi meja tidak valid"))
        } catch (error: IllegalStateException) {
            return EngineStartPlan.Unavailable(Redaction.message(error.message ?: "Konfigurasi meja tidak valid"))
        }
        return EngineStartPlan.Ready(
            EngineConfig(
                cameraId = camera.id.value,
                modelPath = model,
                onnxRuntimePath = runtime,
                tablesJson = json,
                inferenceFps = inferenceFps,
                debounceMs = debounceMs,
            ),
        )
    }

    companion object {
        const val DEFAULT_INFERENCE_FPS: Double = 2.0
        const val DEFAULT_DEBOUNCE_MS: ULong = 2_000uL

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
    data class Unavailable(val reason: String) : EngineStartPlan
}

enum class EngineStartSkip {
    Demo,
    NoTables,
}