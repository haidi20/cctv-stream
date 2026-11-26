package id.warung.monitor.feature.onboarding.domain

/**
 * Facts read from the device before the PIN screen.
 *
 * Every field is a primitive or an enum so this type stays JVM-testable. No
 * Android type, SSID, URL, or filesystem path may enter it: the report built
 * from it is copied to the clipboard.
 */
data class DeviceCapability(
    val apiLevel: Int,
    val abis: List<String>,
    val ramTotalMb: Int,
    val ramAvailableMb: Int,
    val storageFreeMb: Int,
    val modelBundled: Boolean,
    val nativeLibraryLoadable: Boolean,
    val notificationPermissionGranted: Boolean,
    val wifiConnected: Boolean,
    val appVersionCode: Long,
    val appVersionName: String,
) {
    val isArm64: Boolean get() = abis.any { it == ARM64_ABI }

    companion object {
        const val ARM64_ABI: String = "arm64-v8a"
    }
}

/** Outcome of `System.loadLibrary`, without carrying the raw throwable. */
enum class NativeLoad {
    Loaded,
    Failed,
}

/**
 * Runs a native load call and contains [UnsatisfiedLinkError].
 *
 * Unit-testable: pass a lambda that throws. The app passes
 * `{ System.loadLibrary("core_ffi") }`.
 */
fun probeNativeLibrary(load: () -> Unit): NativeLoad {
    return try {
        load()
        NativeLoad.Loaded
    } catch (_: UnsatisfiedLinkError) {
        NativeLoad.Failed
    } catch (_: Throwable) {
        NativeLoad.Failed
    }
}

/** Reads the live capability facts. The app supplies the Android implementation. */
interface DeviceCapabilitySource {
    /** Version code used to re-run the check after an APK update. */
    suspend fun currentVersionCode(): Long

    suspend fun read(): DeviceCapability
}

/**
 * Loads the ONNX model and runs one synthetic inference.
 *
 * Implemented in `app` because the UniFFI binding lives there; the onboarding
 * module only owns the contract and the UI mapping.
 */
fun interface EngineWarmUp {
    suspend fun warmUp(): WarmUpResult
}