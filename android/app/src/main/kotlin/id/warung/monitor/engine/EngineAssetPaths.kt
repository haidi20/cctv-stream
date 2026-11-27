package id.warung.monitor.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the two paths `Engine.start` requires.
 *
 * The model is optional: a missing asset returns `null` so the planner can
 * report `Unavailable` honestly. Nothing here logs a path, and no placeholder
 * file is created for a missing model.
 */
@Singleton
class EngineAssetPaths @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val lock = Any()
    private var cachedRuntimePath: String? = null

    fun modelPath(): String? = synchronized(lock) {
        val target = File(File(context.filesDir, MODEL_DIR), MODEL_NAME)
        if (target.isFile && target.length() > 0L) return target.absolutePath
        try {
            context.assets.open("$MODEL_DIR/$MODEL_NAME").use { input ->
                target.parentFile?.mkdirs()
                val part = File(target.parentFile, "$MODEL_NAME.part")
                part.outputStream().use { output -> input.copyTo(output) }
                Files.move(
                    part.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            target.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        } catch (_: IOException) {
            null
        }
    }

    fun onnxRuntimePath(): String? = synchronized(lock) {
        cachedRuntimePath?.let { return it }
        val extracted = File(context.applicationInfo.nativeLibraryDir, ONNX_RUNTIME_NAME)
        resolveRuntimePath(extracted) { System.loadLibrary(ONNX_RUNTIME_LIBRARY) }
            ?.also { cachedRuntimePath = it }
    }

    companion object {
        const val MODEL_DIR: String = "models"
        const val MODEL_NAME: String = "yolov11n.onnx"
        const val ONNX_RUNTIME_NAME: String = "libonnxruntime.so"
        const val ONNX_RUNTIME_LIBRARY: String = "onnxruntime"

        /**
         * Path handed to Rust for `dlopen`.
         *
         * API 29 usually extracts native libraries into `nativeLibraryDir`, so
         * that absolute path is preferred when it exists. Android 15+ (this app
         * targets 36) instead maps libraries straight out of the APK and leaves
         * the directory empty. Loading the soname once through the process
         * linker and returning its bare name makes Rust's later
         * `dlopen("libonnxruntime.so")` reuse the same mapping, so no 36 MB
         * copy is written to internal storage on a low-spec phone.
         */
        internal fun resolveRuntimePath(
            extracted: File,
            loadRuntimeSoname: () -> Unit,
        ): String? {
            if (extracted.isFile) return extracted.absolutePath
            return runCatching {
                loadRuntimeSoname()
                ONNX_RUNTIME_NAME
            }.getOrNull()
        }
    }
}
