package id.warung.monitor.engine

import android.os.PowerManager
import java.util.Locale

/**
 * One row of the device soak CSV.
 *
 * Every measurement is nullable on purpose: a device that hides `/proc`, a
 * battery broadcast that never arrives, or an engine that has not inferred yet
 * must leave the cell empty. Writing a zero would make a missing reading look
 * like a real measurement and hide exactly the leak the soak exists to find.
 */
data class SoakSample(
    val elapsedSeconds: Long,
    val pssKb: Long? = null,
    val javaHeapKb: Long? = null,
    val nativeHeapKb: Long? = null,
    val rustRssKb: Long? = null,
    val cpuPercent: Double? = null,
    val batteryLevelPercent: Int? = null,
    val batteryTempC: Double? = null,
    val thermalStatus: String? = null,
    val dataKb: Long? = null,
    val framesIn: Long? = null,
    val framesSampled: Long? = null,
    val framesDropped: Long? = null,
    val inferenceP50Ms: Long? = null,
    val inferenceP95Ms: Long? = null,
)

/**
 * CSV rendering for [SoakSample].
 *
 * The header is the contract `docs/performance.md` section 10.2 prints, so a
 * spreadsheet opened from the pulled file needs no renaming.
 */
object SoakCsv {

    const val HEADER: String =
        "elapsed_s,pss_kb,java_heap_kb,native_heap_kb,rss_kb,cpu_pct,level_pct," +
            "battery_temp_c,thermal_status,data_kb,frames_in,frames_sampled," +
            "frames_dropped,inference_p50_ms,inference_p95_ms"

    /** One line per sample; missing values stay empty instead of becoming `0`. */
    fun row(sample: SoakSample): String = listOf(
        sample.elapsedSeconds.toString(),
        sample.pssKb?.toString().orEmpty(),
        sample.javaHeapKb?.toString().orEmpty(),
        sample.nativeHeapKb?.toString().orEmpty(),
        sample.rustRssKb?.toString().orEmpty(),
        sample.cpuPercent?.let { formatOneDecimal(it) }.orEmpty(),
        sample.batteryLevelPercent?.toString().orEmpty(),
        sample.batteryTempC?.let { formatOneDecimal(it) }.orEmpty(),
        escape(sample.thermalStatus),
        sample.dataKb?.toString().orEmpty(),
        sample.framesIn?.toString().orEmpty(),
        sample.framesSampled?.toString().orEmpty(),
        sample.framesDropped?.toString().orEmpty(),
        sample.inferenceP50Ms?.toString().orEmpty(),
        sample.inferenceP95Ms?.toString().orEmpty(),
    ).joinToString(",")

    private fun formatOneDecimal(value: Double): String =
        if (value.isFinite()) String.format(Locale.ROOT, "%.1f", value) else ""

    /** Thermal names come from the platform, but a comma would break the CSV. */
    private fun escape(value: String?): String {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return if (text.any { it == ',' || it == '"' }) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else {
            text
        }
    }
}

/**
 * CPU percentage from `/proc/self/stat`, measured between two reads.
 *
 * `utime + stime` counts scheduler ticks, not seconds; [clockTicksPerSecond]
 * comes from `sysconf(_SC_CLK_TCK)` on the device. The first call returns
 * `null` because a single sample has no interval to divide by, and a stat line
 * that cannot be parsed resets the baseline instead of reporting a bogus
 * percentage.
 */
class CpuUsageMeter(private val clockTicksPerSecond: Long = DEFAULT_CLOCK_TICKS) {

    private var lastTicks: Long? = null
    private var lastAtMs: Long? = null

    fun sample(statLine: String, nowMs: Long): Double? {
        val ticks = parseCpuTicks(statLine) ?: run {
            lastTicks = null
            lastAtMs = null
            return null
        }
        val previousTicks = lastTicks
        val previousAt = lastAtMs
        lastTicks = ticks
        lastAtMs = nowMs
        if (previousTicks == null || previousAt == null) return null
        val elapsedMs = nowMs - previousAt
        if (elapsedMs <= 0) return null
        val tickDelta = ticks - previousTicks
        if (tickDelta < 0) return null
        val seconds = elapsedMs / 1000.0
        return tickDelta.toDouble() / clockTicksPerSecond / seconds * 100.0
    }

    companion object {
        /** Linux USER_HZ default; the device overrides it with `sysconf`. */
        const val DEFAULT_CLOCK_TICKS: Long = 100L

        private val WHITESPACE = Regex("\\s+")

        /**
         * `utime + stime` from a `/proc/<pid>/stat` line.
         *
         * The command name is wrapped in parentheses and may contain spaces, so
         * parsing starts after the last `)`. From there field 3 (`state`) is
         * index 0 and fields 14/15 (`utime`/`stime`) are index 11/12.
         */
        fun parseCpuTicks(statLine: String): Long? {
            val close = statLine.lastIndexOf(')')
            if (close < 0 || close + 2 >= statLine.length) return null
            val fields = statLine.substring(close + 1).trim().split(WHITESPACE)
            if (fields.size < 13) return null
            val utime = fields[11].toLongOrNull() ?: return null
            val stime = fields[12].toLongOrNull() ?: return null
            if (utime < 0 || stime < 0) return null
            return utime + stime
        }
    }
}

/**
 * Names for `PowerManager.currentThermalStatus`.
 *
 * The soak CSV stores the platform name rather than the integer so a report
 * can be read without the API table. An unknown value stays `null` instead of
 * being rounded down to `NONE`, which would hide a hot device.
 */
object ThermalStatusNames {

    fun of(status: Int?): String? = when (status) {
        null -> null
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> null
    }
}