package id.warung.monitor.engine

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.model.MappingFrame
import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.TableConfig
import id.warung.monitor.core.model.TableConfigSet
import id.warung.monitor.core.model.TableId
import id.warung.monitor.core.model.TableLabel
import id.warung.monitor.core.model.TablePolygon
import id.warung.monitor.feature.tables.data.StoredTableConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStartPlannerTest {

    private val planner = EngineStartPlanner()

    private fun camera(source: CameraSource = rtsp()): CameraProfile = CameraProfile(
        id = CameraId("cam-1"),
        label = CameraLabel("Kamera"),
        source = source,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun rtsp(): CameraSource.Rtsp = CameraSource.Rtsp(
        host = "192.168.1.10",
        port = 554,
        path = "/stream",
    )

    private fun stored(revision: Long = 1L, tables: Int = 1): StoredTableConfig {
        val points = listOf(
            NormalizedPoint(0.1, 0.1),
            NormalizedPoint(0.3, 0.1),
            NormalizedPoint(0.3, 0.3),
        )
        val polygon = TablePolygon.orNull(points) ?: error("fixture polygon invalid")
        val config = TableConfigSet(
            frame = MappingFrame(1280, 720, "16:9"),
            tables = (1..tables).map { index ->
                TableConfig(
                    id = TableId("meja-%02d".format(index)),
                    label = TableLabel("Meja $index"),
                    polygon = polygon,
                )
            },
        )
        return StoredTableConfig("cam-1", revision, config, 0L)
    }

    @Test
    fun demo_camera_never_starts_the_native_engine() {
        val plan = planner.plan(
            camera = camera(CameraSource.Demo),
            stored = stored(),
            modelPath = "/model.onnx",
            runtimePath = "/libonnxruntime.so",
        )
        assertEquals(EngineStartPlan.Skipped(EngineStartSkip.Demo), plan)
    }

    @Test
    fun a_camera_without_a_saved_map_skips_start() {
        val plan = planner.plan(camera(), stored = null, modelPath = "/m", runtimePath = "/r")
        assertEquals(EngineStartPlan.Skipped(EngineStartSkip.NoTables), plan)
    }

    @Test
    fun an_empty_map_skips_start() {
        val plan = planner.plan(
            camera = camera(),
            stored = stored(tables = 0),
            modelPath = "/m",
            runtimePath = "/r",
        )
        assertEquals(EngineStartPlan.Skipped(EngineStartSkip.NoTables), plan)
    }

    @Test
    fun a_missing_model_is_reported_without_a_path() {
        val plan = planner.plan(camera(), stored(), modelPath = null, runtimePath = "/r")
        assertTrue(plan is EngineStartPlan.Unavailable)
        assertEquals("Model ONNX belum tersedia", (plan as EngineStartPlan.Unavailable).reason)
    }

    @Test
    fun a_missing_runtime_is_reported_without_a_path() {
        val plan = planner.plan(camera(), stored(), modelPath = "/m", runtimePath = null)
        assertTrue(plan is EngineStartPlan.Unavailable)
        assertEquals("ONNX Runtime belum tersedia", (plan as EngineStartPlan.Unavailable).reason)
    }

    @Test
    fun a_blank_path_counts_as_missing() {
        val plan = planner.plan(camera(), stored(), modelPath = "  ", runtimePath = "/r")
        assertTrue(plan is EngineStartPlan.Unavailable)
    }

    @Test
    fun a_ready_camera_builds_the_engine_config() {
        val plan = planner.plan(
            camera = camera(),
            stored = stored(),
            modelPath = "/data/models/yolov11n.onnx",
            runtimePath = "/data/lib/libonnxruntime.so",
        )
        val config = (plan as EngineStartPlan.Ready).config
        assertEquals("cam-1", config.cameraId)
        assertEquals("/data/models/yolov11n.onnx", config.modelPath)
        assertEquals("/data/lib/libonnxruntime.so", config.onnxRuntimePath)
        assertEquals(2.0, config.inferenceFps, 0.0)
        assertEquals(2_000uL, config.debounceMs)
        assertTrue(config.tablesJson.contains("\"version\""))
    }

    @Test
    fun the_planner_keeps_the_defaults_from_the_performance_doc() {
        assertEquals(2.0, EngineStartPlanner.DEFAULT_INFERENCE_FPS, 0.0)
        assertEquals(2_000uL, EngineStartPlanner.DEFAULT_DEBOUNCE_MS)
    }

    @Test
    fun the_planner_pins_the_measured_ort_thread_count() {
        val plan = planner.plan(
            camera = camera(),
            stored = stored(),
            modelPath = "/data/models/yolov11n.onnx",
            runtimePath = "/data/lib/libonnxruntime.so",
        )
        val config = (plan as EngineStartPlan.Ready).config
        // Measured on SM-A176B: two session threads were faster (p50 192-204 ms)
        // than three (208-256 ms) or four (229-238 ms) and cost one less worker.
        assertEquals(2u, config.intraThreads)
    }

    @Test
    fun a_lite_phone_is_capped_to_a_lower_inference_rate() {
        val lite = planner.withInferenceFps(EngineStartPlanner.LITE_INFERENCE_FPS)
        val plan = lite.plan(
            camera = camera(),
            stored = stored(),
            modelPath = "/data/models/yolov11n.onnx",
            runtimePath = "/data/lib/libonnxruntime.so",
        )
        val config = (plan as EngineStartPlan.Ready).config
        assertEquals(1.5, config.inferenceFps, 0.0)
        assertEquals("debounce must not change with the profile", 2_000uL, config.debounceMs)
    }

    @Test
    fun the_lite_cap_stays_inside_the_device_compatibility_band() {
        // device-compatibility.md allows 1-2 FPS for `lite`.
        assertTrue(EngineStartPlanner.LITE_INFERENCE_FPS in 1.0..2.0)
        assertTrue(EngineStartPlanner.LITE_INFERENCE_FPS < EngineStartPlanner.DEFAULT_INFERENCE_FPS)
    }

    @Test
    fun a_two_gigabyte_phone_is_below_the_inference_floor() {
        // device-compatibility.md marks 2 GB as `blocked`, so the threshold is
        // zero inference rather than a slow one that would end in an abort.
        assertEquals(0.0, EngineStartPlanner.inferenceFpsFor(2_048L), 0.0)
    }

    @Test
    fun a_three_gigabyte_phone_is_capped_at_one_frame_per_second() {
        assertEquals(
            EngineStartPlanner.LOW_RAM_INFERENCE_FPS,
            EngineStartPlanner.inferenceFpsFor(3_072L),
            0.0,
        )
        assertEquals(1.0, EngineStartPlanner.inferenceFpsFor(EngineStartPlanner.MIN_RAM_MB), 0.0)
    }

    @Test
    fun a_phone_below_the_hard_floor_never_infers() {
        assertEquals(0.0, EngineStartPlanner.inferenceFpsFor(2_560L - 1L), 0.0)
    }

    @Test
    fun a_four_gigabyte_phone_keeps_the_requested_rate() {
        assertEquals(2.0, EngineStartPlanner.inferenceFpsFor(4_096L, 2.0), 0.0)
        assertEquals(1.5, EngineStartPlanner.inferenceFpsFor(4_096L, 1.5), 0.0)
    }

    @Test
    fun a_missing_ram_reading_does_not_invent_a_throttle() {
        assertEquals(2.0, EngineStartPlanner.inferenceFpsFor(null, 2.0), 0.0)
    }

    @Test
    fun a_ram_threshold_reaches_the_engine_config() {
        // The threshold must survive the whole seam, not just the helper: a
        // 2 GB phone must not start the engine, and a 3 GB phone must tell
        // Rust to infer at 1 FPS instead of the 2 FPS default.
        val twoGb = planner
            .withInferenceFps(EngineStartPlanner.inferenceFpsFor(2_048L))
            .plan(camera(), stored(), modelPath = "model.onnx", runtimePath = "libonnxruntime.so")
        assertTrue(twoGb is EngineStartPlan.Unavailable)

        val threeGb = planner
            .withInferenceFps(EngineStartPlanner.inferenceFpsFor(3_072L, 2.0))
            .plan(camera(), stored(), modelPath = "model.onnx", runtimePath = "libonnxruntime.so")
        assertEquals(1.0, (threeGb as EngineStartPlan.Ready).config.inferenceFps, 0.0)
    }

    @Test
    fun a_zero_rate_planner_reports_unavailable_instead_of_ready() {
        val plan = planner.withInferenceFps(0.0).plan(
            camera = camera(),
            stored = stored(),
            modelPath = "model.onnx",
            runtimePath = "libonnxruntime.so",
        )

        assertTrue(plan is EngineStartPlan.Unavailable)
    }
}
