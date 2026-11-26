package id.warung.monitor.core.ui.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Local wall-clock formatting shared by every feature screen.
 *
 * All maths runs on epoch milliseconds so callers never juggle platform
 * `Date` objects and JVM tests stay deterministic with a fixed [zone].
 */
object TimeFormat {

    private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Formats [epochMillis] as local `HH:mm:ss`, matching the Home caption. */
    fun clock(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /**
     * Human age of [epochMillis] relative to [nowMillis], e.g. "12 detik lalu".
     *
     * Falls back to a clock reading once the age crosses an hour so a stale
     * snapshot is still pinned to a real time instead of an ever-growing number.
     * A future timestamp (clock skew) is reported as "baru saja" rather than a
     * negative age.
     */
    fun age(epochMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val ageSeconds = (nowMillis - epochMillis) / 1_000L
        return when {
            ageSeconds < 1L -> "baru saja"
            ageSeconds < 60L -> "$ageSeconds detik lalu"
            ageSeconds < 3_600L -> "${ageSeconds / 60L} menit lalu"
            else -> "${clock(epochMillis, zone)}"
        }
    }
}