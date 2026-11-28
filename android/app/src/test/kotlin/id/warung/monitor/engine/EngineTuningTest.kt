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

/**
 * The policy that turns a measured p95 into a rate this phone can sustain.
 *
 * Every number here comes from `docs/performance.md` section 6.3: p50 192-204 ms
 * and p95 296 ms at two threads on an SM-A176B with zero dropped frames over a
 * 30 minute soak at 2 FPS.
 */
class EngineTuningTest {

    @Test
    fun an_unmeasured_device_keeps_the_documented_default() {
        val tuning = EngineTuning.of(p95Ms = null)

        assertEquals(2.0, tuning.inferenceFps, 0.0)
        assertEquals(2u, tuning.intraThreads)
    }

    @Test
    fun a_zero_latency_means_not_measured_rather_than_infinitely_fast() {
        assertEquals(2.0, EngineTuning.of(p95Ms = 0L).inferenceFps, 0.0)
    }

    @Test
    fun a_negative_latency_is_treated_as_not_measured() {
        assertEquals(1.5, EngineTuning.of(p95Ms = -5L, requestedFps = 1.5).inferenceFps, 0.0)
    }

    @Test
    fun the_measured_healthy_phone_keeps_two_fps() {
        // p95 296 ms against an 80% budget of 400 ms for a 500 ms slot.
        assertEquals(2.0, EngineTuning.of(p95Ms = 296L).inferenceFps, 0.0)
    }

    @Test
    fun a_phone_whose_p95_does_not_fit_the_slot_drops_one_step() {
        // 450 ms > 400 ms at 2 FPS, but well inside 533 ms at 1.5 FPS.
        assertEquals(1.5, EngineTuning.of(p95Ms = 450L).inferenceFps, 0.0)
    }

    @Test
    fun a_very_slow_phone_falls_to_the_one_frame_per_second_floor() {
        assertEquals(1.0, EngineTuning.of(p95Ms = 900L).inferenceFps, 0.0)
    }

    @Test
    fun the_floor_is_one_fps_not_zero_because_zero_means_no_inference() {
        assertEquals(1.0, EngineTuning.of(p95Ms = 30_000L).inferenceFps, 0.0)
        assertTrue("zero must never be produced by a latency rule", 0.0 !in EngineTuning.CANDIDATE_RATES)
    }

    @Test
    fun a_lite_ceiling_is_never_raised_by_a_good_measurement() {
        val tuning = EngineTuning.of(p95Ms = 120L, requestedFps = EngineStartPlanner.LITE_INFERENCE_FPS)

        assertEquals(1.5, tuning.inferenceFps, 0.0)
    }

    @Test
    fun a_ram_throttled_rate_is_never_raised_either() {
        // The 3 GB RAM cap is 1 FPS; a fast measurement must not undo it.
        assertEquals(1.0, EngineTuning.of(p95Ms = 100L, requestedFps = 1.0).inferenceFps, 0.0)
    }

    @Test
    fun a_stopped_planner_stays_stopped() {
        assertEquals(0.0, EngineTuning.of(p95Ms = 200L, requestedFps = 0.0).inferenceFps, 0.0)
    }

    @Test
    fun the_stored_thread_count_survives_the_latency_decision() {
        val tuning = EngineTuning.of(p95Ms = 500L, requestedThreads = 3u)

        assertEquals("no measurement justifies changing threads yet", 3u, tuning.intraThreads)
        assertEquals(1.5, tuning.inferenceFps, 0.0)
    }

    @Test
    fun the_slot_budget_matches_the_documented_ceiling() {
        assertEquals(400.0, EngineTuning.slotBudgetMs(2.0), 0.001)
        assertEquals(533.333, EngineTuning.slotBudgetMs(1.5), 0.01)
        assertEquals(800.0, EngineTuning.slotBudgetMs(1.0), 0.001)
    }

    @Test
    fun a_planner_carries_the_tuning_into_the_engine_config() {
        val config = (EngineStartPlanner()
            .withTuning(EngineTuning(inferenceFps = 1.5, intraThreads = 3u))
            .plan(
                camera = camera(),
                stored = stored(),
                modelPath = "model.onnx",
                runtimePath = "libonnxruntime.so",
            ) as EngineStartPlan.Ready).config

        assertEquals(1.5, config.inferenceFps, 0.0)
        assertEquals(3u, config.intraThreads)
        assertEquals("debounce must not follow the tuning", 2_000uL, config.debounceMs)
    }

    @Test
    fun the_tuning_override_does_not_leak_into_the_default_planner() {
        val tuned = EngineStartPlanner().withTuning(EngineTuning(1.0, 4u))
        val fresh = EngineStartPlanner()

        assertEquals(1.0, tuned.inferenceFps, 0.0)
        assertEquals(2.0, fresh.inferenceFps, 0.0)
        assertEquals(2u, fresh.intraThreads)
    }

    private fun camera(): CameraProfile = CameraProfile(
        id = CameraId("cam-1"),
        label = CameraLabel("Kamera"),
        source = CameraSource.Rtsp(host = "192.168.1.10", port = 554, path = "/stream"),
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun stored(): StoredTableConfig {
        val points = listOf(
            NormalizedPoint(0.1, 0.1),
            NormalizedPoint(0.3, 0.1),
            NormalizedPoint(0.3, 0.3),
        )
        val polygon = TablePolygon.orNull(points) ?: error("fixture polygon invalid")
        val config = TableConfigSet(
            frame = MappingFrame(1280, 720, "16:9"),
            tables = listOf(
                TableConfig(
                    id = TableId("meja-01"),
                    label = TableLabel("Meja 1"),
                    polygon = polygon,
                ),
            ),
        )
        return StoredTableConfig("cam-1", 1L, config, 0L)
    }
}
