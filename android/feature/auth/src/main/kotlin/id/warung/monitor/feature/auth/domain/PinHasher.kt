package id.warung.monitor.feature.auth.domain

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Hash seam so tests can count derivations without paying PBKDF2 cost. */
interface PinHasher {
    val iterations: Int

    fun newSalt(): ByteArray

    fun hash(pin: CharArray, salt: ByteArray): ByteArray

    fun verify(pin: CharArray, credential: PinCredential): Boolean
}

/**
 * PBKDF2-HMAC-SHA256 at the iteration count required by the app plan.
 *
 * The PIN never leaves this class: the caller passes a char array and the
 * temporary key spec is cleared as soon as derivation finishes.
 */
class Pbkdf2PinHasher(
    override val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) : PinHasher {

    override fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    override fun hash(pin: CharArray, salt: ByteArray): ByteArray =
        derive(pin, salt, iterations)

    /**
     * Constant-time comparison; a timing side channel on a 6-digit PIN is
     * cheap to exploit otherwise.
     */
    override fun verify(pin: CharArray, credential: PinCredential): Boolean {
        val expected = credential.hash
        if (expected.isEmpty()) return false
        val actual = derive(pin, credential.salt, credential.iterations)
        return try {
            MessageDigest.isEqual(actual, expected)
        } finally {
            actual.fill(0)
        }
    }

    private fun derive(pin: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, rounds, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS: Int = 210_000
        const val SALT_BYTES: Int = 16
        const val HASH_BITS: Int = 256
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    }
}