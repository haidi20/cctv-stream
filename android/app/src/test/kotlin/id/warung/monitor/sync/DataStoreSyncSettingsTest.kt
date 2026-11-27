package id.warung.monitor.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `docs/mvp-priority.md` K-P1-2: DataStore keeps the base URL, the bearer token
 * lives behind a device key. Robolectric is required because DataStore picks
 * `Files.move` over `File.renameTo` only from API 26.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DataStoreSyncSettingsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** DataStore owns file creation, so the target must not exist yet. */
    private fun dataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "$name.preferences_pb") },
        )

    @Test
    fun a_saved_token_never_reaches_the_preferences_file() = runTest {
        val tokens = FakeSyncTokenStore()
        val settings = DataStoreSyncSettings(dataStore("token"), tokens)

        settings.saveBaseUrl("https://vps.example.id/")
        settings.saveToken("tok-abc")

        assertEquals("https://vps.example.id", settings.configuredBaseUrl())
        assertEquals("tok-abc", settings.endpoint()?.token)
        assertTrue(settings.hasToken())
        assertEquals("tok-abc", tokens.stored)
        val raw = String(
            File(tempFolder.root, "token.preferences_pb").readBytes(),
            Charsets.ISO_8859_1,
        )
        assertFalse("the token must not be readable in DataStore", raw.contains("tok-abc"))
    }

    @Test
    fun a_legacy_plaintext_token_moves_into_device_key_storage() = runTest {
        val prefs = dataStore("legacy")
        prefs.edit { mutable ->
            mutable[KEY_BASE_URL] = "https://vps.example.id"
            mutable[KEY_LEGACY_TOKEN] = "legacy-tok"
        }
        val tokens = FakeSyncTokenStore()
        val settings = DataStoreSyncSettings(prefs, tokens)

        assertEquals("legacy-tok", settings.endpoint()?.token)

        assertEquals("legacy-tok", tokens.stored)
        assertNull(prefs.data.first()[KEY_LEGACY_TOKEN])
    }

    @Test
    fun removing_the_token_keeps_the_saved_base_url() = runTest {
        val tokens = FakeSyncTokenStore()
        val settings = DataStoreSyncSettings(dataStore("removed"), tokens)
        settings.saveBaseUrl("https://vps.example.id")
        settings.saveToken("tok-abc")

        settings.saveToken(null)

        assertNull(settings.endpoint()?.token)
        assertEquals("https://vps.example.id", settings.configuredBaseUrl())
        assertFalse(settings.hasToken())
        assertNull(tokens.stored)
    }

    @Test
    fun clearing_forgets_the_url_and_the_token() = runTest {
        val tokens = FakeSyncTokenStore()
        val settings = DataStoreSyncSettings(dataStore("cleared"), tokens)
        settings.saveBaseUrl("https://vps.example.id")
        settings.saveToken("tok-abc")

        settings.clear()

        assertNull(settings.endpoint())
        assertNull(settings.configuredBaseUrl())
        assertNull(tokens.stored)
    }

    @Test
    fun an_insecure_base_url_never_reaches_storage() = runTest {
        val tokens = FakeSyncTokenStore()
        val settings = DataStoreSyncSettings(dataStore("insecure"), tokens)

        runCatching { settings.saveBaseUrl("http://vps.example.id") }

        assertNull(settings.configuredBaseUrl())
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("sync_base_url")
        val KEY_LEGACY_TOKEN = stringPreferencesKey("sync_token")
    }
}

/** Records what reached device-key storage; the real encryption is in `KeystoreSyncTokenStoreTest`. */
private class FakeSyncTokenStore : SyncTokenStore {
    var stored: String? = null

    override suspend fun read(): String? = stored

    override suspend fun hasToken(): Boolean = stored != null

    override suspend fun write(token: String?) {
        stored = token?.trim()?.takeIf { it.isNotEmpty() }
    }
}
