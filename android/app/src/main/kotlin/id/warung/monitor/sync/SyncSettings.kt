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
 * DataStore-backed [SyncSettings].
 *
 * The token is stored in DataStore for now. `docs/server-api.md` section 3
 * wants a Keystore-backed enrollment; that is a later step and is listed as
 * unfinished in `docs/status.md` rather than implied by this class.
 */
@Singleton
class DataStoreSyncSettings(
    private val dataStore: DataStore<Preferences>,
) : SyncSettings {

    override suspend fun endpoint(): SyncEndpoint? {
        val prefs = dataStore.data.first()
        val baseUrl = prefs[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            SyncEndpoint(baseUrl, prefs[KEY_TOKEN]?.takeIf { it.isNotBlank() })
        }.getOrNull()
    }

    override suspend fun configuredBaseUrl(): String? =
        dataStore.data.first()[KEY_BASE_URL]?.takeIf { it.isNotBlank() }

    override suspend fun hasToken(): Boolean =
        !dataStore.data.first()[KEY_TOKEN].isNullOrBlank()

    override suspend fun saveBaseUrl(baseUrl: String) {
        // Validate before writing: a non-HTTPS URL must never reach storage and
        // then fail silently on every worker run.
        SyncEndpoint(baseUrl.trim().trimEnd('/'), token = null)
        dataStore.edit { prefs -> prefs[KEY_BASE_URL] = baseUrl.trim().trimEnd('/') }
    }

    override suspend fun saveToken(token: String?) {
        dataStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(KEY_TOKEN) else prefs[KEY_TOKEN] = token.trim()
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_BASE_URL)
            prefs.remove(KEY_TOKEN)
        }
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("sync_base_url")
        val KEY_TOKEN = stringPreferencesKey("sync_token")
    }
}

/** One DataStore per process. */
val Context.syncDataStore by preferencesDataStore(name = "sync")

