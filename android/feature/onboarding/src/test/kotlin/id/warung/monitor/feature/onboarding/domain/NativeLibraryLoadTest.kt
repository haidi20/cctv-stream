package id.warung.monitor.feature.onboarding.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeLibraryLoadTest {

    @Test
    fun unsatisfied_link_error_becomes_failed_instead_of_crashing() {
        val result = probeNativeLibrary { throw UnsatisfiedLinkError("libcore_ffi.so not found") }

        assertEquals(NativeLoad.Failed, result)
    }

    @Test
    fun other_loader_failures_are_contained_too() {
        val result = probeNativeLibrary { throw IllegalStateException("loader rejected") }

        assertEquals(NativeLoad.Failed, result)
    }

    @Test
    fun a_successful_load_is_reported_as_loaded() {
        var calls = 0
        val result = probeNativeLibrary { calls += 1 }

        assertEquals(NativeLoad.Loaded, result)
        assertEquals(1, calls)
    }
}