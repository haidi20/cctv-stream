package id.warung.monitor.engine

import id.warung.monitor.feature.cameras.session.CountingReason
import id.warung.monitor.feature.cameras.session.CountingState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every planner cause must reach the UI as its own reason.
 *
 * A single generic "tidak menghitung" text would put the user back where they
 * started: an em dash with no explanation (`docs/kotlin-rust-gaps.md` G-02).
 */
class CountingDecisionTest {

    @Test
    fun a_demo_camera_is_reported_as_demo() {
        assertEquals(
            CountingState.Inactive(CountingReason.Demo),
            EngineStartPlan.Skipped(EngineStartSkip.Demo).toCountingState(),
        )
    }

    @Test
    fun a_camera_without_a_saved_map_is_reported_as_no_tables() {
        assertEquals(
            CountingState.Inactive(CountingReason.NoTables),
            EngineStartPlan.Skipped(EngineStartSkip.NoTables).toCountingState(),
        )
    }

    @Test
    fun a_missing_model_is_reported_as_model_missing() {
        assertEquals(
            CountingState.Inactive(CountingReason.ModelMissing),
            EngineStartPlan.Unavailable("x", EngineStartBlocker.ModelMissing).toCountingState(),
        )
    }

    @Test
    fun a_missing_runtime_is_reported_as_runtime_missing() {
        assertEquals(
            CountingState.Inactive(CountingReason.RuntimeMissing),
            EngineStartPlan.Unavailable("x", EngineStartBlocker.RuntimeMissing).toCountingState(),
        )
    }

    @Test
    fun too_little_ram_is_reported_as_ram_too_low() {
        assertEquals(
            CountingState.Inactive(CountingReason.RamTooLow),
            EngineStartPlan.Unavailable("x", EngineStartBlocker.RamTooLow).toCountingState(),
        )
    }

    @Test
    fun an_unencodable_map_is_reported_as_config_invalid() {
        assertEquals(
            CountingState.Inactive(CountingReason.ConfigInvalid),
            EngineStartPlan.Unavailable("x", EngineStartBlocker.ConfigInvalid).toCountingState(),
        )
    }

    @Test
    fun an_unnamed_blocker_still_reports_a_failure() {
        // Silence is the bug this guard exists to prevent: a cause the enum
        // does not name yet must still produce a sentence.
        assertEquals(
            CountingState.Inactive(CountingReason.StartFailed),
            EngineStartPlan.Unavailable("x", EngineStartBlocker.Unknown).toCountingState(),
        )
    }
}
