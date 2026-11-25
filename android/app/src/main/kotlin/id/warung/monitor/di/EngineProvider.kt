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
 * and every caller sees `null` instead.
 */
@Singleton
class EngineProvider @Inject constructor() {

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var failed = false

    @Synchronized
    fun engineOrNull(): Engine? {
        engine?.let { return it }
        if (failed) return null
        return try {
            Engine().also { engine = it }
        } catch (error: Throwable) {
            failed = true
            null
        }
    }
}