package id.warung.monitor.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import id.warung.monitor.core.model.DetectionPreset
import id.warung.monitor.core.sync.DetectionSetting
import id.warung.monitor.core.sync.DetectionSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Detection preset in the same DataStore as the sync endpoint and the retention
 * window (`docs/detection-quality-todo.md` Q-05).
 *
 * An absent value means "never chosen" and resolves to
 * [DetectionPreset.DEFAULT] without a note. A value this build cannot parse is
 * reported through [DetectionSetting.unknownStoredValue] so the screen can say
 * the fallback happened, instead of pretending the user picked it.
 */
@Singleton
class DataStoreDetectionSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : DetectionSettings {

    override suspend fun load(): DetectionSetting {
        val stored = dataStore.data.first()[KEY_PRESET]
            ?: return DetectionSetting(DetectionPreset.DEFAULT)
        val parsed = DetectionPreset.parse(stored)
            ?: return DetectionSetting(DetectionPreset.DEFAULT, unknownStoredValue = true)
        return DetectionSetting(parsed)
    }

    override suspend fun save(preset: DetectionPreset) {
        dataStore.edit { prefs -> prefs[KEY_PRESET] = preset.wireName }
    }

    private companion object {
        val KEY_PRESET = stringPreferencesKey("detection_preset")
    }
}
