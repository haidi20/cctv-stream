package id.warung.monitor.engine

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host coverage for the soak CSV and the `/proc` CPU meter.
 *
 * The CSV is the artifact a 30-minute run is judged by, so a missing cell must
 * stay empty (never `0`) and a thermal name containing a comma must not shift
 * every later column.
 */
class SoakMetricsTest {

    @Test
    fun the_header_matches_the_documented_contract() {
        assertEquals(
            "elapsed_s,pss_kb,java_heap_kb,native_heap_kb,rss_kb,cpu_pct,level_pct," +
                "battery_temp_c,thermal_status,data_kb,frames_in,frames_sampled," +
                "frames_dropped,inference_p50_ms,inference_p95_ms",
            SoakCsv.HEADER,
        )
        assertEquals(15, SoakCsv.HEADER.split(',').size)
    }

    @Test
    fun a_full_sample_renders_one_row_in_header_order() {
        val row = SoakCsv.row(
            SoakSample(
                elapsedSeconds = 60,
                pssKb = 154_321,
                javaHeapKb = 12_345,
                nativeHeapKb = 67_890,
                rustRssKb = 98_765,
                cpuPercent = 12.34,
                batteryLevelPercent = 87,
                batteryTempC = 38.46,
                thermalStatus = "LIGHT",
                dataKb = 4_096,
                framesIn = 120,
                framesSampled = 119,
                framesDropped = 1,
                inferenceP50Ms = 205,
                inferenceP95Ms = 260,
            ),
        )
        assertEquals(
            "60,154321,12345,67890,98765,12.3,87,38.5,LIGHT,4096,120,119,1,205,260",
            row,
        )
        assertEquals(SoakCsv.HEADER.split(',').size, row.split(',').size)
    }

    @Test
    fun an_unmeasurable_sample_leaves_cells_empty_instead_of_zero() {
        val row = SoakCsv.row(SoakSample(elapsedSeconds = 0))
        assertEquals("0,,,,,,,,,,,,,,", row)
        assertEquals(SoakCsv.HEADER.split(',').size, row.split(',').size)
    }

    @Test
    fun a_thermal_name_with_a_comma_is_quoted_so_columns_do_not_shift() {
        val row = SoakCsv.row(
            SoakSample(elapsedSeconds = 5, thermalStatus = "HOT, \"BAD\""),
        )
        assertEquals("5,,,,,,,,\"HOT, \"\"BAD\"\"\",,,,,,", row)
    }

    @Test
    fun a_non_finite_reading_is_dropped_rather_than_printed_as_nan() {
        val row = SoakCsv.row(
            SoakSample(
                elapsedSeconds = 1,
                cpuPercent = Double.NaN,
                batteryTempC = Double.POSITIVE_INFINITY,
            ),
        )
        assertTrue("NaN must not reach the CSV", !row.contains("NaN"))
        assertTrue("Infinity must not reach the CSV", !row.contains("Infinity"))
    }

    @Test
    fun cpu_ticks_are_read_after_a_process_name_that_contains_spaces() {
        // /proc/<pid>/stat: field 1 is the pid, field 2 the command in
        // parentheses (spaces allowed), fields 14/15 are utime/stime.
        val stat = "1234 (id.warung monitor app) S 1 1234 1234 0 -1 4194560 " +
            "100 0 0 0 11 22 0 0 20 0 10 0 500 1000000 100 18446744073709551615"
        assertEquals(33L, CpuUsageMeter.parseCpuTicks(stat))
    }

    @Test
    fun a_truncated_stat_line_reports_null_instead_of_a_partial_sum() {
        assertNull(CpuUsageMeter.parseCpuTicks(""))
        assertNull(CpuUsageMeter.parseCpuTicks("1234 (app) S 1 2 3"))
        assertNull(CpuUsageMeter.parseCpuTicks("1234 (app) S 1 2 3 4 5 6 7 8 9 10"))
    }

    @Test
    fun a_non_numeric_or_negative_tick_field_is_rejected() {
        val base = "1 (app) S 1 2 3 4 5 6 7 8 9 10 11 12 13"
        assertNull(CpuUsageMeter.parseCpuTicks(base.replace(" 11 12 ", " x 12 ")))
        assertNull(CpuUsageMeter.parseCpuTicks(base.replace(" 11 12 ", " 11 -2 ")))
    }

    @Test
    fun the_first_cpu_sample_has_no_interval_and_returns_null() {
        val meter = CpuUsageMeter(clockTicksPerSecond = 100)
        assertNull(meter.sample(STAT_10_TICKS, nowMs = 1_000))
    }

    @Test
    fun the_second_sample_reports_the_delta_over_the_elapsed_time() {
        val meter = CpuUsageMeter(clockTicksPerSecond = 100)
        meter.sample(STAT_10_TICKS, nowMs = 1_000)
        // 50 ticks gained over 1 s at 100 Hz is half a core: 50%.
        assertEquals(50.0, meter.sample(STAT_60_TICKS, nowMs = 2_000)!!, 0.001)
    }

    @Test
    fun an_unparseable_sample_resets_the_baseline_instead_of_reporting_a_spike() {
        val meter = CpuUsageMeter(clockTicksPerSecond = 100)
        meter.sample(STAT_10_TICKS, nowMs = 1_000)
        assertNull(meter.sample("garbage", nowMs = 2_000))
        assertNull("the next good line must start a new baseline", meter.sample(STAT_60_TICKS, nowMs = 3_000))
        assertEquals(0.0, meter.sample(STAT_60_TICKS, nowMs = 4_000)!!, 0.001)
    }

    @Test
    fun a_clock_that_does_not_advance_returns_null_and_a_rewound_tick_counter_does_too() {
        val meter = CpuUsageMeter(clockTicksPerSecond = 100)
        meter.sample(STAT_10_TICKS, nowMs = 1_000)
        assertNull(meter.sample(STAT_60_TICKS, nowMs = 1_000))

        val rewound = CpuUsageMeter(clockTicksPerSecond = 100)
        rewound.sample(STAT_60_TICKS, nowMs = 1_000)
        assertNull(rewound.sample(STAT_10_TICKS, nowMs = 2_000))
    }

    @Test
    fun thermal_names_are_named_and_unknown_values_stay_unset() {
        assertEquals("NONE", ThermalStatusNames.of(PowerManager.THERMAL_STATUS_NONE))
        assertEquals("MODERATE", ThermalStatusNames.of(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals("SEVERE", ThermalStatusNames.of(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals("SHUTDOWN", ThermalStatusNames.of(PowerManager.THERMAL_STATUS_SHUTDOWN))
        assertNull(ThermalStatusNames.of(null))
        assertNull(ThermalStatusNames.of(99))
    }

    private companion object {
        private const val STAT_10_TICKS =
            "1 (app) S 1 2 3 4 5 6 7 8 9 10 4 6 0 0 20 0 1 0 1 1 1"
        private const val STAT_60_TICKS =
            "1 (app) S 1 2 3 4 5 6 7 8 9 10 30 30 0 0 20 0 1 0 1 1 1"
    }
}