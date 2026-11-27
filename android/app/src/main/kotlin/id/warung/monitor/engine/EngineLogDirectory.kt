package id.warung.monitor.engine

import android.system.Os
import java.io.File

/**
 * Writes one process environment variable.
 *
 * A function interface instead of a direct [Os] call keeps the policy testable on
 * the host, where `android.system.Os` is only a stub.
 */
fun interface ProcessEnvironment {

    /** @return `true` when the variable now holds [value]. */
    fun set(name: String, value: String): Boolean
}

/** Android implementation backed by `setenv(3)`. Best effort: it never throws. */
object AndroidProcessEnvironment : ProcessEnvironment {

    override fun set(name: String, value: String): Boolean =
        runCatching { Os.setenv(name, value, true) }.isSuccess
}

/**
 * Gives `core-ffi` a writable directory for its rolling JSON log.
 *
 * `core-ffi::observability` reads `CCTV_LOG_DIR` and otherwise falls back to
 * `std::env::temp_dir()`, which on Android resolves to `/tmp` - a path no app
 * process may write. The Rust logger does not fail then, it drops its output
 * silently (`try_appender` returns `None`), so the variable has to be exported
 * before the first `Engine()` call or no native log exists at all.
 *
 * Preparation is best effort. A phone where the directory cannot be created
 * still runs the app, it only loses the native log file, and the failure is
 * reported as `null` instead of an exception during start-up.
 */
class EngineLogDirectory(
    private val environment: ProcessEnvironment,
    private val directory: File,
) {

    /**
     * Creates [directory] and exports it as `CCTV_LOG_DIR`.
     *
     * @return the absolute log path, or `null` when the directory is unusable or
     *   the variable could not be set.
     */
    fun prepare(): String? {
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val path = directory.absolutePath
        return if (environment.set(ENV_VAR, path)) path else null
    }

    companion object {

        /**
         * Read by `core-ffi::observability::init` through `std::env`. Renaming it
         * on one side silently sends the native log back to `/tmp`.
         */
        const val ENV_VAR: String = "CCTV_LOG_DIR"

        /** Subdirectory of `filesDir`. The Rust side rotates three files itself. */
        const val DIR_NAME: String = "logs"
    }
}
