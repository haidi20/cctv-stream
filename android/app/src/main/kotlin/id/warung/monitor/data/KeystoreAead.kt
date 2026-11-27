package id.warung.monitor.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** IV length of every AES-GCM blob this app writes to app-private storage. */
const val AEAD_IV_BYTES = 12

/**
 * An AES-GCM blob: [AEAD_IV_BYTES]-byte IV followed by ciphertext and the tag.
 *
 * Callers only pass plaintext through; key handling stays at the leaf so a
 * caller cannot accidentally persist a key or a plaintext.
 */
interface AeadCrypto {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * Android Keystore AES-256-GCM for one [keyAlias].
 *
 * The key is generated on first use, never leaves the device, and is not
 * exported by an app backup; a wiped key makes old blobs undecryptable, which
 * each store treats as "no secret" rather than as a crash
 * (`docs/android-app-plan.md` D10).
 */
class AndroidKeystoreAead(private val keyAlias: String) : AeadCrypto {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE).also { it.load(null) }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = cipher(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > AEAD_IV_BYTES) { "encrypted blob is too short" }
        val cipher = cipher(Cipher.DECRYPT_MODE, secretKey(), blob.copyOfRange(0, AEAD_IV_BYTES))
        return cipher.doFinal(blob, AEAD_IV_BYTES, blob.size - AEAD_IV_BYTES)
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
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

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
