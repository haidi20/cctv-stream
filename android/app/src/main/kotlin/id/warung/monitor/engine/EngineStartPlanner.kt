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
    private val inferenceFps: Double = DEFAULT_INFERENCE_FPS,
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