package id.warung.monitor.engine

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thermal and memory readings for [InferenceCadence].
 *
 * An interface, not a concrete class, so the pump's cadence decision can be
 * tested on the host with known values instead of whatever the test runner's
 * virtual device reports. A missing reading is `null`: "not measured" must not
 * be treated as "hot".
 */
interface DeviceLoadSource {
    fun thermalStatus(): Int?

    fun availableRamMb(): Long?

    /**
     * Total physical RAM in MB, or `null` when the platform cannot report it.
     *
     * Used only to pick a safe inference rate for a 2 GB device
     * (`docs/performance.md` section 9); it is not a display value.
     */
    fun ramTotalMb(): Long? = null
}

/**
 * Real readings from the platform.
 *
 * Both getters are cheap binder reads, called once per pump tick (every
 * 500-1000 ms), never per frame. No value here leaves the process or reaches a
 * log.
 */
@Singleton
class AndroidDeviceLoadSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DeviceLoadSource {

    override fun thermalStatus(): Int? {
        val power = context.getSystemService(PowerManager::class.java) ?: return null
        return power.currentThermalStatus
    }

    override fun ramTotalMb(): Long? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem / (1024L * 1024L)
    }

    override fun availableRamMb(): Long? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem / MIB
    }

    private companion object {
        const val MIB: Long = 1024L * 1024L
    }
}