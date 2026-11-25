package id.warung.monitor.feature.auth.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    private val hasher = Pbkdf2PinHasher(iterations = 1_000)

    @Test
    fun same_salt_produces_same_hash() {
        val salt = hasher.newSalt()
        val first = hasher.hash("246810".toCharArray(), salt)
        val second = hasher.hash("246810".toCharArray(), salt)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun a_different_salt_produces_a_different_hash() {
        val first = hasher.hash("246810".toCharArray(), hasher.newSalt())
        val second = hasher.hash("246810".toCharArray(), hasher.newSalt())
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun verification_accepts_the_right_pin_and_rejects_neighbours() {
        val salt = hasher.newSalt()
        val credential = PinCredential(salt, hasher.hash("246810".toCharArray(), salt), 1_000)

        assertTrue(hasher.verify("246810".toCharArray(), credential))
        assertFalse(hasher.verify("246811".toCharArray(), credential))
        assertFalse(hasher.verify("".toCharArray(), credential))
    }

    @Test
    fun new_salt_is_16_bytes_and_not_all_zero() {
        val salt = hasher.newSalt()
        assertTrue(salt.size == 16)
        assertNotEquals(0, salt.count { it.toInt() != 0 })
    }
}