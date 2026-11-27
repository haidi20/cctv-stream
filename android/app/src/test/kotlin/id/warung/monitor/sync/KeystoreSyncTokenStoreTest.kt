package id.warung.monitor.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.warung.monitor.data.AeadCrypto
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `docs/mvp-priority.md` K-P1-2: the VPS bearer token must not sit in plaintext
 * DataStore or in a readable app-private file.
 *
 * Robolectric cannot host the AndroidKeyStore provider, so a JVM AES-GCM key
 * stands in. The blob layout asserted here - 12-byte IV followed by ciphertext
 * and tag - is the layout `AndroidKeystoreAead` writes on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeystoreSyncTokenStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val encryptedFile: File
        get() = File(context.filesDir, "sync/token.bin")

    private fun store(key: String = KEY_A) = KeystoreSyncTokenStore(context, JvmAead(key))

    @Test
    fun a_saved_token_round_trips_from_encrypted_storage() = runTest {
        val store = store()

        store.write("tok-abc")

        assertEquals("tok-abc", store.read())
        assertTrue(store.hasToken())
        val raw = String(encryptedFile.readBytes(), Charsets.ISO_8859_1)
        assertFalse("the token must not be readable on disk", raw.contains("tok-abc"))
    }

    @Test
    fun an_unenrolled_store_has_no_token() = runTest {
        val store = store()

        assertNull(store.read())
        assertFalse(store.hasToken())
        assertFalse(encryptedFile.exists())
    }

    @Test
    fun clearing_the_token_deletes_the_encrypted_file() = runTest {
        val store = store()
        store.write("tok-abc")

        store.write(null)

        assertNull(store.read())
        assertFalse(store.hasToken())
        assertFalse(encryptedFile.exists())
    }

    @Test
    fun a_blank_token_is_stored_as_no_token() = runTest {
        val store = store()
        store.write("tok-abc")

        store.write("   ")

        assertNull(store.read())
        assertFalse(encryptedFile.exists())
    }

    @Test
    fun a_truncated_blob_is_dropped_instead_of_wedging_sync() = runTest {
        encryptedFile.parentFile!!.mkdirs()
        encryptedFile.writeBytes(byteArrayOf(1, 2, 3))

        assertNull(store().read())
        assertFalse(encryptedFile.exists())
    }

    @Test
    fun a_token_encrypted_with_a_lost_key_is_forgotten() = runTest {
        store(KEY_A).write("tok-abc")

        // Wiping the app's Keystore key leaves an undecryptable blob behind; the
        // store must treat it as "no token" so the user can enroll again.
        val otherKey = store(KEY_B)

        assertNull(otherKey.read())
        assertFalse(encryptedFile.exists())
    }

    private companion object {
        const val KEY_A = "0123456789abcdef0123456789abcdef"
        const val KEY_B = "fedcba9876543210fedcba9876543210"
    }
}

/** JVM AES-GCM stands in for AndroidKeyStore, which Robolectric cannot host. */
private class JvmAead(secret: String) : AeadCrypto {
    private val key = javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "AES")

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            key,
            javax.crypto.spec.GCMParameterSpec(128, blob.copyOfRange(0, 12)),
        )
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}
