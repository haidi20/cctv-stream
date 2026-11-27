package id.warung.monitor.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.warung.monitor.data.AEAD_IV_BYTES
import id.warung.monitor.data.AeadCrypto
import id.warung.monitor.data.AndroidKeystoreAead
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val KEY_ALIAS = "cctv_sync_token"
private const val DIRECTORY = "sync"
private const val FILE_NAME = "token.bin"

/**
 * The VPS bearer token, kept behind a device key (`docs/mvp-priority.md` K-P1-2,
 * `docs/server-api.md` section 3).
 *
 * The value is write-only for callers: they can ask [hasToken] and hand the
 * value to a transport, but nothing in the UI ever holds it. This is the test
 * seam for storage, mirroring `CameraSecretStore`.
 */
interface SyncTokenStore {
    suspend fun read(): String?

    /** True when a token exists and still decrypts. */
    suspend fun hasToken(): Boolean

    /** Replaces the token; `null` or a blank value removes it. */
    suspend fun write(token: String?)
}

/**
 * AES-GCM encrypted token file under app-private storage.
 *
 * Only IV + ciphertext touch the disk; the key stays in Android Keystore and is
 * not exported by a backup. A rotated or wiped key leaves an undecryptable blob,
 * which is deleted so sync reports "no token" and the user can enroll again
 * instead of every request failing.
 */
@Singleton
class KeystoreSyncTokenStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SyncTokenStore {

    private var crypto: AeadCrypto? = null

    /** Test seam: Robolectric has no AndroidKeyStore provider. */
    constructor(context: Context, crypto: AeadCrypto) : this(context) {
        this.crypto = crypto
    }

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val file = tokenFile()
        if (!file.exists()) return@withContext null
        val bytes = file.readBytes()
        if (bytes.size <= AEAD_IV_BYTES) {
            file.delete()
            return@withContext null
        }
        try {
            String(crypto().decrypt(bytes), Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // Never throw from a read: a worker that cannot decrypt must behave
            // like an unenrolled device, not retry a lost key forever.
            file.delete()
            null
        }
    }

    override suspend fun hasToken(): Boolean = read() != null

    override suspend fun write(token: String?) = withContext(Dispatchers.IO) {
        val file = tokenFile()
        val trimmed = token?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) {
            file.delete()
            return@withContext Unit
        }
        val encrypted = crypto().encrypt(trimmed.toByteArray(Charsets.UTF_8))
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(encrypted)
        if (!temp.renameTo(file)) {
            // renameTo can fail on some OEM filesystems; a direct write still
            // stays in app-private storage and replaces the blob only once it
            // is complete.
            file.writeBytes(encrypted)
            temp.delete()
        }
        Unit
    }

    private fun crypto(): AeadCrypto = crypto ?: AndroidKeystoreAead(KEY_ALIAS).also { crypto = it }

    private fun tokenFile(): File = File(directory(), FILE_NAME)

    private fun directory(): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }
}
