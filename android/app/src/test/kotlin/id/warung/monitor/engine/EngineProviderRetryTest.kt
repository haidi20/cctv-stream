package id.warung.monitor.engine

import id.warung.monitor.di.EngineProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A native load failure must be diagnosable and recoverable after a reset. */
class EngineProviderRetryTest {

    @Test
    fun a_reset_allows_one_more_load_attempt_after_an_abi_failure() {
        val provider = EngineProvider()
        var attempts = 0
        provider.loaderForTest = {
            attempts += 1
            if (attempts == 1) throw UnsatisfiedLinkError("wrong ABI")
            it.invoke()
        }

        assertNull("first load must not fabricate an engine", provider.engineOrNull())
        assertNull("without a reset the failure stays cached", provider.engineOrNull())
        assertEquals("failure must stay cached until reset", 1, attempts)

        provider.resetForRetry()
        assertNull("no .so exists in the JVM, so the retry is also null", provider.engineOrNull())
        assertEquals("a retry must reach the loader", 2, attempts)
        assertTrue(
            "the error class is the only safe diagnostic on device",
            provider.lastLoadErrorForTest in setOf("UnsatisfiedLinkError", "NoClassDefFoundError"),
        )
    }
}
