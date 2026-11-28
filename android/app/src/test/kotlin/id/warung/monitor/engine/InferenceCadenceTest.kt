package id.warung.monitor.engine

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cadence rules must match docs/performance.md section 5 and monitoring 2.4. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InferenceCadenceTest {

    private val cadence = InferenceCadence()

    @Test
    fun a_cool_phone_with_ram_keeps_two_fps() {
        assertEquals(
            InferenceCadence.NORMAL_DELAY_MS,
            cadence.delayMillis(
                thermalStatus = PowerManager.THERMAL_STATUS_NONE,
                availableRamMb = 900,
                requestedFps = InferenceCadence.NORMAL_FPS,
            ),
        )
    }

    @Test
    fun an_unknown_reading_does_not_slow_the_pump() {
        assertEquals(
            "no reading is not evidence of heat",
            InferenceCadence.NORMAL_DELAY_MS,
            cadence.delayMillis(thermalStatus = null, availableRamMb = null, requestedFps = 2.0),
        )
    }

    @Test
    fun moderate_heat_alone_stays_at_the_normal_rate() {
        assertEquals(
            "MODERATE is still inside the 42-48 C band, not the 1 FPS band",
            InferenceCadence.NORMAL_DELAY_MS,
            cadence.delayMillis(
                thermalStatus = PowerManager.THERMAL_STATUS_MODERATE,
                availableRamMb = 900,
                requestedFps = 2.0,
            ),
        )
    }

    @Test
    fun severe_heat_halves_the_requested_rate() {
        assertEquals(
            "2 FPS under SEVERE becomes the documented 1 FPS",
            InferenceCadence.SLOW_DELAY_MS,
            cadence.delayMillis(
                thermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
                availableRamMb = 900,
                requestedFps = 2.0,
            ),
        )
    }

    @Test
    fun a_benchmark_request_is_respected_instead_of_pinned_to_two_fps() {
        assertEquals(
            "the hard test asks for 10-15 FPS on a 4 GB phone",
            1_000L / 15,
            cadence.delayMillis(
                thermalStatus = PowerManager.THERMAL_STATUS_NONE,
                availableRamMb = 900,
                requestedFps = 15.0,
            ),
        )
    }

    @Test
    fun the_benchmark_ceiling_is_twenty_four_fps() {
        assertEquals(
            1_000L / 24,
            cadence.millisPerFrame(InferenceCadence.MAX_FPS),
        )
    }

    @Test
    fun a_request_above_the_ceiling_is_capped_not_accepted() {
        assertEquals(
            "capture may never run faster than the Rust sampler ceiling",
            1_000L / 24,
            cadence.millisPerFrame(60.0),
        )
    }

    @Test
    fun a_non_finite_request_falls_back_to_the_floor() {
        assertEquals(
            "0.5 FPS is the documented floor; 2000 ms per frame",
            2_000L,
            cadence.millisPerFrame(Double.NaN),
        )
    }

    @Test
    fun low_free_ram_drops_to_one_fps_even_when_cool() {
        assertEquals(
            InferenceCadence.SLOW_DELAY_MS,
            cadence.delayMillis(
                thermalStatus = PowerManager.THERMAL_STATUS_NONE,
                availableRamMb = InferenceCadence.LOW_RAM_MB - 1,
                requestedFps = 2.0,
            ),
        )
    }

    @Test
    fun the_delay_shrinks_by_the_time_the_tick_already_spent() {
        assertEquals(
            "500 ms pacing minus 20 ms of work leaves 480 ms",
            480L,
            cadence.paceDelayMillis(intervalMs = 500L, workMs = 20L),
        )
    }

    @Test
    fun a_tick_that_takes_exactly_the_interval_needs_no_extra_delay() {
        assertEquals(0L, cadence.paceDelayMillis(intervalMs = 500L, workMs = 500L))
    }

    @Test
    fun a_tick_slower_than_the_interval_never_adds_a_delay() {
        assertEquals(
            "a 900 ms inference at 2 FPS must not wait another 500 ms",
            0L,
            cadence.paceDelayMillis(intervalMs = 500L, workMs = 900L),
        )
    }

    @Test
    fun negative_work_measurements_are_ignored() {
        assertEquals(
            "a clock glitch must not lengthen the interval",
            500L,
            cadence.paceDelayMillis(intervalMs = 500L, workMs = -25L),
        )
    }

    @Test
    fun twenty_four_fps_compensates_work_so_the_rate_is_not_halved() {
        assertEquals(
            16L,
            cadence.paceDelayMillis(intervalMs = 1_000L / 24, workMs = 25L),
        )
    }

    @Test
    fun exactly_the_ram_threshold_is_still_normal() {
        assertEquals(
            "the documented band is below 150 MB, not at it",
            InferenceCadence.NORMAL_DELAY_MS,
            cadence.delayMillis(
                thermalStatus = PowerManager.THERMAL_STATUS_NONE,
                availableRamMb = InferenceCadence.LOW_RAM_MB,
                requestedFps = 2.0,
            ),
        )
    }

    @Test
    fun severe_heat_is_reported_as_throttled() {
        assertEquals(true, cadence.isThrottled(PowerManager.THERMAL_STATUS_SEVERE, 900))
        assertEquals(true, cadence.isThrottled(PowerManager.THERMAL_STATUS_CRITICAL, 900))
    }

    @Test
    fun low_free_ram_is_reported_as_throttled() {
        assertEquals(
            true,
            cadence.isThrottled(PowerManager.THERMAL_STATUS_NONE, InferenceCadence.LOW_RAM_MB - 1),
        )
    }

    @Test
    fun a_cool_phone_with_ram_is_not_throttled() {
        assertEquals(
            false,
            cadence.isThrottled(PowerManager.THERMAL_STATUS_MODERATE, InferenceCadence.LOW_RAM_MB),
        )
    }

    @Test
    fun unknown_readings_are_never_throttled() {
        assertEquals(
            "not measured is not the same as hot or low on memory",
            false,
            cadence.isThrottled(null, null),
        )
    }
}
