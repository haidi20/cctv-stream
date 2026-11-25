package id.warung.monitor.feature.auth.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import id.warung.monitor.feature.auth.domain.PinCredential
import java.util.Base64
import kotlinx.coroutines.flow.first

/**
 * DataStore implementation of [AuthStore].
 *
 * The lockout deadline is stored as `elapsedRealtime` milliseconds, not wall
 * clock: changing the device date cannot unlock the app. After a reboot that
 * clock restarts, so a persisted deadline is discarded while the failure count
 * is kept.
 */
class DataStoreAuthStore(
    private val dataStore: DataStore<Preferences>,
    private val bootToken: () -> String,
) : AuthStore {

    override suspend fun credential(): PinCredential? {
        val prefs = dataStore.data.first()
        val salt = prefs[KEY_SALT]?.let(::decode) ?: return null
        val hash = prefs[KEY_HASH]?.let(::decode) ?: return null
        val iterations = prefs[KEY_ITERATIONS] ?: return null
        return PinCredential(salt = salt, hash = hash, iterations = iterations)
    }

    override suspend fun saveCredential(credential: PinCredential) {
        dataStore.edit { prefs ->
            prefs[KEY_SALT] = encode(credential.salt)
            prefs[KEY_HASH] = encode(credential.hash)
            prefs[KEY_ITERATIONS] = credential.iterations
        }
    }

    override suspend fun isSetupComplete(): Boolean =
        dataStore.data.first()[KEY_SETUP_COMPLETE] == true

    override suspend fun markSetupComplete() {
        dataStore.edit { it[KEY_SETUP_COMPLETE] = true }
    }

    override suspend fun failures(): Int = dataStore.data.first()[KEY_FAILURES] ?: 0

    override suspend fun lockedUntilElapsedMs(): Long? {
        val prefs = dataStore.data.first()
        val deadline = prefs[KEY_LOCKED_UNTIL] ?: return null
        val storedToken = prefs[KEY_BOOT_TOKEN] ?: return null
        if (storedToken != bootToken()) {
            dataStore.edit { it.remove(KEY_LOCKED_UNTIL) }
            return null
        }
        return deadline
    }

    override suspend fun recordFailure(failures: Int, lockedUntilElapsedMs: Long?) {
        dataStore.edit { prefs ->
            prefs[KEY_FAILURES] = failures
            if (lockedUntilElapsedMs == null) {
                prefs.remove(KEY_LOCKED_UNTIL)
            } else {
                prefs[KEY_LOCKED_UNTIL] = lockedUntilElapsedMs
                prefs[KEY_BOOT_TOKEN] = bootToken()
            }
        }
    }

    override suspend fun resetFailures() {
        dataStore.edit { prefs ->
            prefs[KEY_FAILURES] = 0
            prefs.remove(KEY_LOCKED_UNTIL)
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SALT)
            prefs.remove(KEY_HASH)
            prefs.remove(KEY_ITERATIONS)
            prefs.remove(KEY_SETUP_COMPLETE)
            prefs.remove(KEY_FAILURES)
            prefs.remove(KEY_LOCKED_UNTIL)
            prefs.remove(KEY_BOOT_TOKEN)
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private companion object {
        val KEY_SALT = stringPreferencesKey("pin_salt")
        val KEY_HASH = stringPreferencesKey("pin_hash")
        val KEY_ITERATIONS = intPreferencesKey("pin_iterations")
        val KEY_SETUP_COMPLETE = booleanPreferencesKey("pin_setup_complete")
        val KEY_FAILURES = intPreferencesKey("pin_failures")
        val KEY_LOCKED_UNTIL = longPreferencesKey("pin_locked_until_elapsed")
        val KEY_BOOT_TOKEN = stringPreferencesKey("pin_boot_token")
    }
}

/** One DataStore per process; the property delegate enforces that. */
val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")