package id.warung.monitor.sync

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import id.warung.monitor.core.model.HeartbeatDeviceProfile
import id.warung.monitor.core.model.HeartbeatThermalStatus
import id.warung.monitor.core.model.HeartbeatTransport
import id.warung.monitor.data.DeviceIdStore
import id.warung.monitor.feature.onboarding.data.OnboardingStore
import id.warung.monitor.feature.onboarding.domain.DeviceProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects the device half of a heartbeat.
 *
 * Only wire-safe primitives leave this class: no SSID, IP, MAC, path, or
 * credential. A reading that cannot be taken stays `null`, so the dashboard can
 * tell "not measured" from "measured zero".
 */
@Singleton
class AndroidHeartbeatFacts @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val deviceIds: DeviceIdStore,
    private val session: HeartbeatSession,
    private val onboarding: OnboardingStore,
) {

    suspend fun read(): HeartbeatDeviceFacts = withContext(Dispatchers.IO) {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        HeartbeatDeviceFacts(
            deviceId = deviceIds.deviceId(),
            sessionId = session.current(),
            appVersion = packageInfo.versionName?.takeIf { it.isNotBlank() },
            profile = onboarding.load()?.profile.toWire(),
            ramFreeMb = freeMemoryMb(),
            batteryTempC = batteryTemperature(),
            thermalStatus = thermalStatus(),
            storageFreeMb = freeStorageMb(),
            transport = activeTransport(),
        )
    }

    private fun freeMemoryMb(): Long? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return (info.availMem / MIB).takeIf { it >= 0 }
    }

    private fun freeStorageMb(): Long? {
        val stat = StatFs(context.filesDir.absolutePath)
        return (stat.availableBytes / MIB).takeIf { it >= 0 }
    }

    /** `null` when the device exposes no battery temperature. */
    private fun batteryTemperature(): Double? = runCatching {
        val intent = context.registerReceiver(null, android.content.IntentFilter(
            android.content.Intent.ACTION_BATTERY_CHANGED,
        ))
        val celsius = intent?.getIntExtra("temperature", Int.MIN_VALUE)?.takeIf {
            it != Int.MIN_VALUE
        }?.let { it / 10.0 }
        celsius?.takeIf { it in -40.0..120.0 }
    }.getOrNull()

    private fun thermalStatus(): HeartbeatThermalStatus? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val power = context.getSystemService(PowerManager::class.java) ?: return null
        return when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> HeartbeatThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> HeartbeatThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> HeartbeatThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> HeartbeatThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> HeartbeatThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> HeartbeatThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> HeartbeatThermalStatus.SHUTDOWN
            else -> null
        }
    }

    private fun activeTransport(): HeartbeatTransport? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return HeartbeatTransport.NONE
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return HeartbeatTransport.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> HeartbeatTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                HeartbeatTransport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                HeartbeatTransport.ETHERNET
            else -> HeartbeatTransport.UNKNOWN
        }
    }

    private fun DeviceProfile?.toWire(): HeartbeatDeviceProfile = when (this) {
        DeviceProfile.Full -> HeartbeatDeviceProfile.FULL
        DeviceProfile.Balanced, null -> HeartbeatDeviceProfile.BALANCED
        DeviceProfile.Lite -> HeartbeatDeviceProfile.LITE
        DeviceProfile.Blocked -> HeartbeatDeviceProfile.BLOCKED
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
