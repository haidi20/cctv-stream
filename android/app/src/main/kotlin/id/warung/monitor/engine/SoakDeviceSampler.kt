package id.warung.monitor.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Debug
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import id.warung.monitor.core.EngineHealth
import java.io.File

/**
 * Reads the per-process half of a soak sample.
 *
 * Every getter is a cheap local read taken once per sampling interval
 * (60 s for the 30-minute run, 5 min for the 8-hour run), never per frame.
 * A reading the platform refuses to give stays `null`; the CSV then shows an
 * empty cell instead of a zero that would look like a healthy measurement.
 *
 * No value produced here is logged. Paths and package names never leave the
 * process; only the numbers and the thermal name reach the CSV.
 */
class SoakDeviceSampler(private val context: Context) {

    private val cpu = CpuUsageMeter()

    /**
     * @param health engine snapshot, or `null` when no session is running.
     * @param nowMs monotonic clock; injectable so the CSV order stays testable.
     */
    fun sample(
        elapsedSeconds: Long,
        health: EngineHealth?,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): SoakSample {
        val memory = memory()
        return SoakSample(
            elapsedSeconds = elapsedSeconds,
            pssKb = memory?.pssKb,
            javaHeapKb = memory?.javaHeapKb,
            nativeHeapKb = memory?.nativeHeapKb,
            rustRssKb = health?.rssKb?.toLong()?.takeIf { it > 0L },
            cpuPercent = runCatching {
                cpu.sample(File(PROC_SELF_STAT).readText(), nowMs)
            }.getOrNull(),
            batteryLevelPercent = battery()?.levelPercent,
            batteryTempC = battery()?.temperatureC,
            thermalStatus = ThermalStatusNames.of(thermalStatus()),
            dataKb = DirectoryUsage.sizeKb(File(context.applicationInfo.dataDir)),
            framesIn = health?.framesIn?.toLong(),
            framesSampled = health?.framesSampled?.toLong(),
            framesDropped = health?.framesDropped?.toLong(),
            inferenceP50Ms = health?.inferenceP50Ms?.toLong()?.takeIf { it > 0L },
            inferenceP95Ms = health?.inferenceP95Ms?.toLong()?.takeIf { it > 0L },
        )
    }

    /** Free storage at [directory], in KiB; `null` when `StatFs` fails. */
    fun freeStorageKb(directory: File): Long? = runCatching {
        StatFs(directory.absolutePath).availableBytes / 1024L
    }.getOrNull()

    private fun memory(): Memory? = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        Memory(
            pssKb = info.totalPss.toLong().takeIf { it > 0L },
            javaHeapKb = info.dalvikPss.toLong().takeIf { it >= 0L },
            nativeHeapKb = info.nativePss.toLong().takeIf { it >= 0L },
        )
    }.getOrNull()

    private fun battery(): Battery? = runCatching {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return@runCatching null
        val level = intent.getIntExtra("level", Int.MIN_VALUE)
            .takeIf { it in 0..100 }
        val temperature = intent.getIntExtra("temperature", Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10.0 }
            ?.takeIf { it in -40.0..120.0 }
        if (level == null && temperature == null) null else Battery(level, temperature)
    }.getOrNull()

    private fun thermalStatus(): Int? = runCatching {
        context.getSystemService(PowerManager::class.java)?.currentThermalStatus
    }.getOrNull()

    private data class Memory(
        val pssKb: Long?,
        val javaHeapKb: Long?,
        val nativeHeapKb: Long?,
    )

    private data class Battery(val levelPercent: Int?, val temperatureC: Double?)

    private companion object {
        const val PROC_SELF_STAT = "/proc/self/stat"
    }
}

/**
 * Recursive app-data size, the in-process equivalent of `run-as du -sk .`.
 *
 * Directories that disappear mid-walk (cache eviction) are skipped rather than
 * failing the whole measurement, and a root that cannot be listed reports
 * `null` so the soak does not record a shrinking app as healthy.
 */
internal object DirectoryUsage {

    fun sizeKb(root: File): Long? {
        if (!root.exists()) return null
        var totalBytes = 0L
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            val children = current.listFiles()
            if (children == null) {
                // A file (or an unreadable directory): count the file itself.
                if (current.isFile) totalBytes += current.length()
                continue
            }
            for (child in children) {
                if (child.isDirectory) pending.addLast(child) else totalBytes += child.length()
            }
        }
        return totalBytes / 1024L
    }
}