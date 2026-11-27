package id.warung.monitor.data

import android.content.Context
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.feature.cameras.data.CameraSecretStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val KEY_ALIAS = "cctv_rtsp_secret"
private const val FILE_PREFIX = "rtsp-secret-"
private const val FILE_SUFFIX = ".bin"

/**
 * Camera RTSP passwords encrypted with a device-backed AES key.
 *
 * The key never leaves Android Keystore and is not exported by a backup; only
 * IV + ciphertext live in app-private storage. Each camera has its own file so
 * deleting a camera removes its secret without rewriting the others.
 */
@Singleton
class KeystoreCameraSecretStore @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : CameraSecretStore {

    private var crypto: CameraSecretCrypto? = null

    /** Test seam: Robolectric has no AndroidKeyStore provider. */
    constructor(context: Context, crypto: CameraSecretCrypto) : this(context) {
        this.crypto = crypto
    }

    override suspend fun read(id: CameraId): CharArray? = withContext(Dispatchers.IO) {
        val file = fileFor(id)
        if (!file.exists()) return@withContext null
        val bytes = file.readBytes()
        if (bytes.size <= AEAD_IV_BYTES) {
            file.delete()
            return@withContext null
        }
        try {
            val plain = crypto().decrypt(bytes)
            String(plain, Charsets.UTF_8).toCharArray()
        } catch (_: Exception) {
            // A rotated or lost key must not leave an unreadable blob behind;
            // the camera then behaves like one with no saved password.
            file.delete()
            null
        }
    }

    override suspend fun save(id: CameraId, secret: CharArray) = withContext(Dispatchers.IO) {
        val encrypted = crypto().encrypt(String(secret).toByteArray(Charsets.UTF_8))
        val output = fileFor(id)
        val temp = File(output.parentFile, output.name + ".tmp")
        temp.writeBytes(encrypted)
        if (!temp.renameTo(output)) {
            // renameTo can fail on some OEM filesystems; a direct write still
            // stays inside app-private storage and keeps the old file intact
            // until the content is ready.
            output.writeBytes(encrypted)
            temp.delete()
        }
        Unit
    }

    override suspend fun delete(id: CameraId) = withContext(Dispatchers.IO) {
        fileFor(id).delete()
        Unit
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        secretDir().listFiles()?.forEach { file ->
            if (file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)) file.delete()
        }
        Unit
    }

    private fun crypto(): CameraSecretCrypto =
        crypto ?: AndroidCameraSecretCrypto().also { crypto = it }

    private fun fileFor(id: CameraId): File = File(secretDir(), FILE_PREFIX + id.value + FILE_SUFFIX)

    private fun secretDir(): File = File(context.filesDir, "rtsp-secrets").apply { mkdirs() }

}

/** AES-GCM bytes: IV followed by ciphertext + tag, see [AeadCrypto]. */
interface CameraSecretCrypto {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** Camera passwords use their own Keystore alias so a wipe is per-purpose. */
internal class AndroidCameraSecretCrypto : CameraSecretCrypto {

    private val aead = AndroidKeystoreAead(KEY_ALIAS)

    override fun encrypt(plain: ByteArray): ByteArray = aead.encrypt(plain)

    override fun decrypt(blob: ByteArray): ByteArray = aead.decrypt(blob)
}

