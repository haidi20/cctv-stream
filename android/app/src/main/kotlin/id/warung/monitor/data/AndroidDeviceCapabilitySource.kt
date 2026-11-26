package id.warung.monitor.data

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import id.warung.monitor.engine.EngineAssetPaths
import id.warung.monitor.feature.onboarding.domain.DeviceCapability
import id.warung.monitor.feature.onboarding.domain.DeviceCapabilitySource
import id.warung.monitor.feature.onboarding.domain.NativeLoad
import id.warung.monitor.feature.onboarding.domain.probeNativeLibrary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads Android capability facts.
 *
 * The native load is attempted here, before any UniFFI type is touched: JNA
 * registers symbols lazily, so the first call is what throws. Once loaded, the
 * probe is a no-op on repeat calls. Nothing in this class logs the SSID, a URL,
 * or a path.
 */
@Singleton
class AndroidDeviceCapabilitySource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assets: EngineAssetPaths,
) : DeviceCapabilitySource {

    @Volatile
    private var nativeLoad: Boolean? = null

    override suspend fun currentVersionCode(): Long = withContext(Dispatchers.IO) {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }

    override suspend fun read(): DeviceCapability = withContext(Dispatchers.IO) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val totalMb = (memory.totalMem / MIB).toInt()
        val availableMb = (memory.availMem / MIB).toInt()
        val freeMb = (StatFs(context.filesDir.absolutePath).availableBytes / MIB).toInt()
        DeviceCapability(
            apiLevel = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            ramTotalMb = totalMb,
            ramAvailableMb = availableMb,
            storageFreeMb = freeMb,
            modelBundled = assets.modelPath() != null,
            nativeLibraryLoadable = nativeLoaded(),
            notificationPermissionGranted = notificationsGranted(),
            wifiConnected = wifiConnected(),
            appVersionCode = currentVersionCode(),
            appVersionName = versionName(),
        )
    }

    /**
     * `System.loadLibrary` is idempotent per class loader; a failed attempt is
     * remembered so the report is stable within one process.
     */
    private fun nativeLoaded(): Boolean {
        nativeLoad?.let { return it }
        val result = probeNativeLibrary { System.loadLibrary("core_ffi") }
        val loaded = result == NativeLoad.Loaded
        nativeLoad = loaded
        return loaded
    }

    /**
     * Android 13 introduced the runtime notification permission. Below that the
     * capability is always available, so the row must not warn about a
     * permission the platform never asked for.
     */
    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun wifiConnected(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun versionName(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName.orEmpty()
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}