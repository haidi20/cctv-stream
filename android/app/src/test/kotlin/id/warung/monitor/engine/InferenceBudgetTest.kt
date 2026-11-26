package id.warung.monitor.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pump and Rust sampler must agree on the rate for the active session. */
class InferenceBudgetTest {

    @Test
    fun the_default_matches_the_documented_two_fps() {
        assertEquals(2.0, InferenceBudget().requestedFps(), 0.0001)
    }

    @Test
    fun a_lite_session_publishes_its_lower_rate() {
        val budget = InferenceBudget()
        budget.setRequestedFps(EngineStartPlanner.LITE_INFERENCE_FPS)
        assertEquals(1.5, budget.requestedFps(), 0.0001)
    }

    @Test
    fun a_benchmark_rate_is_kept() {
        val budget = InferenceBudget()
        budget.setRequestedFps(15.0)
        assertEquals(15.0, budget.requestedFps(), 0.0001)
        budget.setRequestedFps(24.0)
        assertEquals(24.0, budget.requestedFps(), 0.0001)
    }

    @Test
    fun an_out_of_band_rate_is_clamped_to_the_sampler_limits() {
        val budget = InferenceBudget()
        budget.setRequestedFps(60.0)
        assertEquals(InferenceCadence.MAX_FPS, budget.requestedFps(), 0.0001)
        budget.setRequestedFps(0.1)
        assertEquals(InferenceCadence.MIN_FPS, budget.requestedFps(), 0.0001)
    }

    @Test
    fun an_invalid_rate_does_not_replace_the_current_one() {
        val budget = InferenceBudget()
        budget.setRequestedFps(15.0)
        budget.setRequestedFps(Double.NaN)
        budget.setRequestedFps(0.0)
        budget.setRequestedFps(-1.0)
        assertEquals(15.0, budget.requestedFps(), 0.0001)
    }

    @Test
    fun closing_a_session_restores_the_default() {
        val budget = InferenceBudget()
        budget.setRequestedFps(24.0)
        budget.reset()
        assertEquals(InferenceBudget.DEFAULT_FPS, budget.requestedFps(), 0.0001)
    }
}