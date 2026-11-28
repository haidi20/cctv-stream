package id.warung.monitor.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import id.warung.monitor.core.model.DetectionPreset
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A fresh install must run the detector defaults, and a value from another
 * build must be reported rather than silently rewritten
 * (`docs/detection-quality-todo.md` Q-05).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DataStoreDetectionSettingsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun dataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "$name.preferences_pb") },
        )

    @Test
    fun a_fresh_install_reports_the_balanced_default_without_a_note() = runTest {
        val setting = DataStoreDetectionSettings(dataStore("fresh")).load()

        assertEquals(DetectionPreset.Balanced, setting.preset)
        assertFalse(setting.unknownStoredValue)
    }

    @Test
    fun a_saved_preset_survives_a_new_instance() = runTest {
        val store = dataStore("saved")
        DataStoreDetectionSettings(store).save(DetectionPreset.Precise)

        val reopened = DataStoreDetectionSettings(store).load()

        assertEquals(DetectionPreset.Precise, reopened.preset)
        assertFalse(reopened.unknownStoredValue)
    }

    @Test
    fun a_value_from_another_build_falls_back_and_says_so() = runTest {
        val store = dataStore("unknown")
        store.edit { prefs -> prefs[stringPreferencesKey("detection_preset")] = "ultra" }

        val setting = DataStoreDetectionSettings(store).load()

        assertEquals(DetectionPreset.Balanced, setting.preset)
        assertTrue(setting.unknownStoredValue)
    }

    @Test
    fun saving_replaces_an_unreadable_value() = runTest {
        val store = dataStore("replace")
        store.edit { prefs -> prefs[stringPreferencesKey("detection_preset")] = "ultra" }
        val settings = DataStoreDetectionSettings(store)

        settings.save(DetectionPreset.Precise)

        val setting = settings.load()
        assertEquals(DetectionPreset.Precise, setting.preset)
        assertFalse(setting.unknownStoredValue)
    }

    @Test
    fun the_stored_key_is_the_documented_name() = runTest {
        val store = dataStore("key")
        DataStoreDetectionSettings(store).save(DetectionPreset.Precise)

        val raw = store.data.first()[stringPreferencesKey("detection_preset")]

        assertEquals("presisi", raw)
    }
}
