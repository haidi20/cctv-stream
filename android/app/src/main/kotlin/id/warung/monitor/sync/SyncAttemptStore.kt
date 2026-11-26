package id.warung.monitor.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Persists how many consecutive sync attempts failed.
 *
 * WorkManager's own `runAttemptCount` resets whenever a new unique request is
 * enqueued, so it cannot drive the 30 s / 1 min / 5 min / 15 min ladder from
 * `docs/monitoring.md` 4.3. This counter survives process death and re-enqueues;
 * a successful drain resets it to zero.
 */
interface SyncAttemptStore {
    suspend fun failureCount(): Int

    suspend fun recordFailure(): Int

    suspend fun reset()
}

class DataStoreSyncAttemptStore(
    private val dataStore: DataStore<Preferences>,
) : SyncAttemptStore {

    override suspend fun failureCount(): Int = dataStore.data.first()[KEY] ?: 0

    override suspend fun recordFailure(): Int {
        var next = 0
        dataStore.edit { prefs ->
            next = ((prefs[KEY] ?: 0) + 1).coerceAtMost(MAX_TRACKED)
            prefs[KEY] = next
        }
        return next
    }

    override suspend fun reset() {
        dataStore.edit { prefs -> prefs.remove(KEY) }
    }

    private companion object {
        val KEY = intPreferencesKey("sync_failure_count")

        /** Past this the ladder is already at its ceiling; keep the number small. */
        const val MAX_TRACKED = 100
    }
}

