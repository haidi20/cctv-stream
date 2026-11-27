package id.warung.monitor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.feature.cameras.data.CameraSecretStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "cctv_rtsp_secret"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val FILE_PREFIX = "rtsp-secret-"
private const val FILE_SUFFIX = ".bin"
private const val GCM_TAG_BITS = 128
private const val IV_BYTES = 12

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
        if (bytes.size <= IV_BYTES) {
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

/** AES-GCM bytes: 12-byte IV followed by ciphertext + tag. */
interface CameraSecretCrypto {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

internal class AndroidCameraSecretCrypto : CameraSecretCrypto {
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE).also { it.load(null) }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = cipher(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val cipher = cipher(Cipher.DECRYPT_MODE, secretKey(), blob.copyOfRange(0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private fun cipher(mode: Int, key: SecretKey, iv: ByteArray? = null): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
}
