package id.warung.monitor.di

import id.warung.monitor.core.Engine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazily creates the Rust engine.
 *
 * `Engine()` triggers JNA's `Native.register`, which throws
 * [UnsatisfiedLinkError] when `libcore_ffi.so` is missing. Creating it eagerly in
 * a Hilt provider would crash the app at launch; here the failure is contained
 * and every caller sees `null` instead. The failure class is kept as a
 * process-local diagnostic without any message text, ABI or path; after a new
 * APK/filesDir is installed the caller can call [resetForRetry] once.
 */
@Singleton
class EngineProvider @Inject constructor() {

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var failed = false

    @Volatile
    var lastLoadErrorForTest: String? = null
        private set

    /** Seam for host tests; production uses the generated UniFFI constructor. */
    internal var loaderForTest: ((() -> Engine) -> Engine)? = null

    @Synchronized
    fun engineOrNull(): Engine? {
        engine?.let { return it }
        if (failed) return null
        val makeEngine: () -> Engine = { loaderForTest?.invoke({ Engine() }) ?: Engine() }
        return try {
            makeEngine().also { engine = it }
        } catch (error: Throwable) {
            failed = true
            lastLoadErrorForTest = error.javaClass.simpleName
            null
        }
    }

    /** Allows one more native load attempt after filesDir or ABI changed. */
    @Synchronized
    fun resetForRetry() {
        failed = false
        lastLoadErrorForTest = null
    }
}
