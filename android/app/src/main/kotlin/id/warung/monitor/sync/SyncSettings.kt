package id.warung.monitor.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import id.warung.monitor.core.sync.SyncEndpoint
import id.warung.monitor.core.sync.SyncSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Base URL in DataStore, bearer token behind a device key.
 *
 * The URL is configuration, not a secret, so plain DataStore is enough. The
 * token goes through [SyncTokenStore] (`docs/mvp-priority.md` K-P1-2); an older
 * build wrote it to the `sync_token` preference, and [migrateLegacyToken] moves
 * such a value once and then deletes the plaintext copy.
 */
@Singleton
class DataStoreSyncSettings(
    private val dataStore: DataStore<Preferences>,
    private val tokens: SyncTokenStore,
) : SyncSettings {

    override suspend fun endpoint(): SyncEndpoint? {
        val baseUrl = configuredBaseUrl() ?: return null
        return runCatching { SyncEndpoint(baseUrl, storedToken()) }.getOrNull()
    }

    override suspend fun configuredBaseUrl(): String? =
        dataStore.data.first()[KEY_BASE_URL]?.takeIf { it.isNotBlank() }

    override suspend fun hasToken(): Boolean {
        migrateLegacyToken()
        return tokens.hasToken()
    }

    override suspend fun saveBaseUrl(baseUrl: String) {
        // Validate before writing: a non-HTTPS URL must never reach storage and
        // then fail silently on every worker run.
        SyncEndpoint(baseUrl.trim().trimEnd('/'), token = null)
        dataStore.edit { prefs -> prefs[KEY_BASE_URL] = baseUrl.trim().trimEnd('/') }
    }

    override suspend fun saveToken(token: String?) {
        tokens.write(token)
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_BASE_URL)
            prefs.remove(KEY_LEGACY_TOKEN)
        }
        tokens.write(null)
    }

    private suspend fun storedToken(): String? {
        migrateLegacyToken()
        return tokens.read()
    }

    /**
     * Token written by a build that predates Keystore storage: encrypt it, then
     * drop the plaintext preference. The write happens first, so an interrupted
     * migration can only repeat itself, never lose the token.
     */
    private suspend fun migrateLegacyToken() {
        val legacy = dataStore.data.first()[KEY_LEGACY_TOKEN]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        tokens.write(legacy)
        dataStore.edit { prefs -> prefs.remove(KEY_LEGACY_TOKEN) }
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("sync_base_url")

        /** Plaintext slot from older builds; read once, then removed. */
        val KEY_LEGACY_TOKEN = stringPreferencesKey("sync_token")
    }
}

/** One DataStore per process. */
val Context.syncDataStore by preferencesDataStore(name = "sync")

