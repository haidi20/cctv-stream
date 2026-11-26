package id.warung.monitor.feature.settings.domain

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule

/**
 * The form is the only way to turn sync on. A bug here leaves every queued row
 * stranded, so the tests cover load, validation, token retention, and failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSettingsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun an_unconfigured_install_shows_an_empty_form() = runTest {
        val viewModel = SyncSettingsViewModel(FakeSyncSettings())
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertEquals("", state.baseUrl)
        assertFalse(state.hasSavedToken)
        assertFalse(state.isConfigured)
    }

    @Test
    fun an_existing_endpoint_is_loaded_without_the_token_value() = runTest {
        val viewModel = SyncSettingsViewModel(
            FakeSyncSettings(baseUrl = "https://vps.contoh.id", token = "rahasia"),
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("https://vps.contoh.id", state.baseUrl)
        assertTrue(state.hasSavedToken)
        assertEquals("", state.tokenDraft)
    }

    @Test
    fun saving_a_valid_url_stores_it_and_reports_success() = runTest {
        val settings = FakeSyncSettings()
        val viewModel = SyncSettingsViewModel(settings)
        advanceUntilIdle()

        var saved = 0
        viewModel.updateBaseUrl("https://vps.contoh.id/")
        viewModel.save { saved += 1 }
        advanceUntilIdle()

        assertEquals(listOf("https://vps.contoh.id"), settings.savedUrls)
        assertEquals(1, saved)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun a_plain_http_url_is_rejected_before_storage_and_saved_is_not_called() = runTest {
        val settings = FakeSyncSettings()
        val viewModel = SyncSettingsViewModel(settings)
        advanceUntilIdle()

        var saved = 0
        viewModel.updateBaseUrl("http://vps.contoh.id")
        viewModel.save { saved += 1 }
        advanceUntilIdle()

        assertTrue(settings.savedUrls.isEmpty())
        assertEquals(0, saved)
        assertEquals(
            "Alamat server harus diawali https://",
            viewModel.state.value.error,
        )
    }

    @Test
    fun a_new_token_is_stored_and_the_field_is_cleared() = runTest {
        val settings = FakeSyncSettings()
        val viewModel = SyncSettingsViewModel(settings)
        advanceUntilIdle()

        viewModel.updateBaseUrl("https://vps.contoh.id")
        viewModel.updateToken("token-baru")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(listOf("token-baru"), settings.savedTokens)
        assertEquals("", viewModel.state.value.tokenDraft)
        assertTrue(viewModel.state.value.hasSavedToken)
    }

    @Test
    fun an_empty_token_field_keeps_the_stored_token() = runTest {
        val settings = FakeSyncSettings(baseUrl = "https://lama.contoh.id", token = "lama")
        val viewModel = SyncSettingsViewModel(settings)
        advanceUntilIdle()

        viewModel.updateBaseUrl("https://baru.contoh.id")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(listOf("https://baru.contoh.id"), settings.savedUrls)
        assertTrue(settings.savedTokens.isEmpty())
        assertTrue(viewModel.state.value.hasSavedToken)
    }

    @Test
    fun clearing_the_token_keeps_the_url() = runTest {
        val settings = FakeSyncSettings(baseUrl = "https://vps.contoh.id", token = "lama")
        val viewModel = SyncSettingsViewModel(settings)
        advanceUntilIdle()

        viewModel.clearToken()
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), settings.savedTokens)
        assertEquals("https://vps.contoh.id", settings.baseUrl)
        assertFalse(viewModel.state.value.hasSavedToken)
    }

    @Test
    fun a_write_failure_is_reported_without_echoing_the_value() = runTest {
        val settings = FakeSyncSettings(failWrites = true)
        val viewModel = SyncSettingsViewModel(settings)
        advanceUntilIdle()

        viewModel.updateBaseUrl("https://vps.contoh.id")
        viewModel.save()
        advanceUntilIdle()

        val error = viewModel.state.value.error
        assertEquals("Gagal menyimpan alamat server", error)
        assertFalse(error.orEmpty().contains("vps.contoh.id"))
        assertFalse(viewModel.state.value.saving)
    }

    @Test
    fun a_read_failure_still_leaves_a_usable_form() = runTest {
        val viewModel = SyncSettingsViewModel(FakeSyncSettings(failReads = true))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertFalse(state.hasSavedToken)
    }

    @Test
    fun a_blank_url_cannot_be_saved() = runTest {
        val viewModel = SyncSettingsViewModel(FakeSyncSettings())
        advanceUntilIdle()

        viewModel.updateBaseUrl("   ")

        assertFalse(viewModel.state.value.canSave)
    }
}
