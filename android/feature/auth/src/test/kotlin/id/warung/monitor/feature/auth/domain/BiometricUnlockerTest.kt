package id.warung.monitor.feature.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricUnlockerTest {

    private class FakeUnlock(
        var available: Boolean = true,
        var prompts: Int = 0,
    ) : BiometricUnlock {
        var success: (() -> Unit)? = null
        var failure: (() -> Unit)? = null
        var cancels: Int = 0

        override fun isAvailable(): Boolean = available

        override fun prompt(onSuccess: () -> Unit, onFailure: () -> Unit) {
            prompts++
            success = onSuccess
            failure = onFailure
        }

        override fun cancel() {
            cancels++
        }
    }

    @Test
    fun no_implementation_means_no_biometric_key() {
        val unlocker = BiometricUnlocker(null)

        assertFalse(unlocker.available)
    }

    @Test
    fun an_unavailable_device_never_opens_a_prompt() {
        val fake = FakeUnlock(available = false)
        val unlocker = BiometricUnlocker(fake)
        var unlocks = 0

        unlocker.request { unlocks++ }

        assertFalse(unlocker.available)
        assertEquals(0, fake.prompts)
        assertEquals(0, unlocks)
    }

    @Test
    fun a_successful_prompt_unlocks_once() {
        val fake = FakeUnlock()
        val unlocker = BiometricUnlocker(fake)
        var unlocks = 0

        unlocker.request { unlocks++ }
        assertEquals(1, fake.prompts)

        fake.success?.invoke()
        fake.success?.invoke()

        assertEquals(1, unlocks)
    }

    @Test
    fun a_second_request_is_ignored_while_the_prompt_is_in_flight() {
        val fake = FakeUnlock()
        val unlocker = BiometricUnlocker(fake)

        unlocker.request { }
        unlocker.request { }

        assertEquals(1, fake.prompts)
    }

    @Test
    fun a_failure_releases_the_slot_for_a_retry() {
        val fake = FakeUnlock()
        val unlocker = BiometricUnlocker(fake)
        var unlocks = 0

        unlocker.request { unlocks++ }
        fake.failure?.invoke()
        unlocker.request { unlocks++ }

        assertEquals(2, fake.prompts)
        assertEquals(0, unlocks)
    }

    @Test
    fun cancel_is_forwarded_to_the_platform_prompt() {
        val fake = FakeUnlock()
        val unlocker = BiometricUnlocker(fake)

        unlocker.cancel()

        assertEquals(1, fake.cancels)
        assertTrue(unlocker.available)
    }
}