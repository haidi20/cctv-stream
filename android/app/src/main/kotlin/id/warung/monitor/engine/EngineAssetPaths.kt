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
 * Resolves the two absolute paths `Engine.start` requires.
 *
 * The model is optional: a missing asset returns `null` so the planner can
 * report `Unavailable` honestly. Nothing here logs a path, and no placeholder
 * file is created for a missing model. The ONNX Runtime is packaged by the
 * Maven AAR into `nativeLibraryDir`, so its path is stable for the process.
 */
@Singleton
class EngineAssetPaths @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val lock = Any()

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

    fun onnxRuntimePath(): String? {
        val library = File(context.applicationInfo.nativeLibraryDir, ONNX_RUNTIME_NAME)
        return library.takeIf { it.isFile }?.absolutePath
    }

    companion object {
        const val MODEL_DIR: String = "models"
        const val MODEL_NAME: String = "yolov11n.onnx"
        const val ONNX_RUNTIME_NAME: String = "libonnxruntime.so"
    }
}