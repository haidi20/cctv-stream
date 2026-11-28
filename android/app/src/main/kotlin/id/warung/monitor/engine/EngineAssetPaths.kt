package id.warung.monitor.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the two paths `Engine.start` requires.
 *
 * The model is optional: a missing asset returns `null` so the planner can
 * report `Unavailable` honestly. Nothing here logs a path, and no placeholder
 * file is created for a missing model.
 *
 * A copied model records the install stamp it came from
 * (`<versionCode>:<lastUpdateTime>`). Without that record an updated APK kept
 * the model a previous version had already written to `filesDir`, so a
 * retrained model never reached a device that had run the app once.
 */
@Singleton
class EngineAssetPaths @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val lock = Any()
    private var cachedRuntimePath: String? = null

    fun modelPath(): String? = synchronized(lock) {
        val dir = File(context.filesDir, MODEL_DIR)
        val target = File(dir, MODEL_NAME)
        val stampFile = File(dir, STAMP_NAME)
        val currentStamp = installedStamp()
        val refresh = planRefresh(
            storedStamp = stampFile.readStamp(),
            currentStamp = currentStamp,
            targetUsable = target.isFile && target.length() > 0L,
            sameContent = { sameContent(target) },
        )
        when (refresh) {
            ModelRefresh.Reuse -> target.absolutePath
            ModelRefresh.Adopt -> {
                writeStamp(stampFile, currentStamp)
                target.absolutePath
            }
            ModelRefresh.Copy -> copyAsset(target, stampFile, currentStamp)
        }
    }

    fun onnxRuntimePath(): String? = synchronized(lock) {
        cachedRuntimePath?.let { return it }
        val extracted = File(context.applicationInfo.nativeLibraryDir, ONNX_RUNTIME_NAME)
        resolveRuntimePath(extracted) { System.loadLibrary(ONNX_RUNTIME_LIBRARY) }
            ?.also { cachedRuntimePath = it }
    }

    /**
     * Install stamp of the running APK, or `null` when the platform refuses.
     *
     * Both parts matter: `lastUpdateTime` changes on every install, and the
     * version code makes the stamp readable when diagnosing a device.
     */
    private fun installedStamp(): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.longVersionCode}:${info.lastUpdateTime}"
    }.getOrNull()

    /**
     * True when the installed model already has the bytes of the bundled one.
     *
     * Only reached after an update or a lost stamp, so the two reads happen
     * once per install rather than on every launch. A missing asset answers
     * `false`, which keeps a previously installed model in place.
     */
    private fun sameContent(target: File): Boolean {
        val assetHash = runCatching {
            sha256Hex(context.assets.open("$MODEL_DIR/$MODEL_NAME"))
        }.getOrNull() ?: return false
        val targetHash = runCatching { sha256Hex(target.inputStream()) }.getOrNull()
        return assetHash == targetHash
    }

    /**
     * Copies the bundled model into place and records the stamp it came from.
     *
     * A missing asset keeps whatever is already installed instead of deleting
     * it; the stale stamp makes the next start try again.
     */
    private fun copyAsset(target: File, stampFile: File, stamp: String?): String? {
        val dir = target.parentFile ?: return null
        dir.mkdirs()
        val part = File(dir, "$MODEL_NAME.part")
        val copied = runCatching {
            context.assets.open("$MODEL_DIR/$MODEL_NAME").use { input ->
                part.outputStream().use { output -> input.copyTo(output) }
            }
        }.isSuccess
        if (!copied) {
            part.delete()
            return target.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        }
        return try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            writeStamp(stampFile, stamp)
            target.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        } catch (_: IOException) {
            null
        }
    }

    /** Best-effort stamp write; a failure only costs one re-hash on the next start. */
    private fun writeStamp(stampFile: File, stamp: String?) {
        if (stamp == null) return
        runCatching { stampFile.writeText(stamp) }
    }

    private fun File.readStamp(): String? =
        runCatching { readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        const val MODEL_DIR: String = "models"
        const val MODEL_NAME: String = "yolov11n.onnx"
        const val STAMP_NAME: String = "$MODEL_NAME.stamp"
        const val ONNX_RUNTIME_NAME: String = "libonnxruntime.so"
        const val ONNX_RUNTIME_LIBRARY: String = "onnxruntime"

        /**
         * Decides what to do with the model already sitting in `filesDir`.
         *
         * Kept pure so the decision is testable without an asset manager. The
         * `sameContent` probe is a lambda because it hashes two files; the
         * steady-state answer ([ModelRefresh.Reuse]) must never pay for it.
         */
        internal fun planRefresh(
            storedStamp: String?,
            currentStamp: String?,
            targetUsable: Boolean,
            sameContent: () -> Boolean,
        ): ModelRefresh = when {
            // Cannot date the install: keep what is there rather than
            // overwriting a model we cannot judge.
            currentStamp == null -> if (targetUsable) ModelRefresh.Reuse else ModelRefresh.Copy
            storedStamp == currentStamp && targetUsable -> ModelRefresh.Reuse
            !targetUsable -> ModelRefresh.Copy
            sameContent() -> ModelRefresh.Adopt
            else -> ModelRefresh.Copy
        }

        /** Lowercase SHA-256 of [input], consumed to the end. */
        internal fun sha256Hex(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

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

/** What [EngineAssetPaths.planRefresh] decided about the installed model. */
internal enum class ModelRefresh {
    /** The stamped copy is current; no file is read. */
    Reuse,

    /** The bytes match the bundle; only the stamp needs writing. */
    Adopt,

    /** The bundle differs, is untracked, or is absent; copy it over the model. */
    Copy,
}
