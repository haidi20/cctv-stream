package id.warung.monitor.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import id.warung.monitor.core.sync.RetentionSettings
import id.warung.monitor.core.sync.RetentionWindow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Retention window in the same DataStore as the sync endpoint
 * (`docs/android-sync.md` sections 2 and 7).
 *
 * A missing or unknown value resolves to [RetentionWindow.DEFAULT] instead of
 * failing: a settings screen that cannot read its own value would leave the user
 * unable to change it. Saving clamps to the documented choices, so a future
 * build cannot persist a window this one does not understand.
 */
@Singleton
class DataStoreRetentionSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : RetentionSettings {

    override suspend fun window(): RetentionWindow =
        RetentionWindow.fromDays(dataStore.data.first()[KEY_RETENTION_DAYS])

    override suspend fun save(window: RetentionWindow) {
        dataStore.edit { prefs -> prefs[KEY_RETENTION_DAYS] = window.days }
    }

    private companion object {
        val KEY_RETENTION_DAYS = intPreferencesKey("retention_days")
    }
}
