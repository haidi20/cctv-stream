package id.warung.monitor.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import id.warung.monitor.core.sync.RetentionWindow
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `docs/android-sync.md` section 7: the window is a plain preference with a
 * documented default, and an unreadable value must never lock the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DataStoreRetentionSettingsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun dataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "$name.preferences_pb") },
        )

    @Test
    fun a_fresh_install_keeps_seven_days() = runTest {
        val settings = DataStoreRetentionSettings(dataStore("fresh"))

        assertEquals(RetentionWindow.DEFAULT, settings.window())
        assertEquals(7, settings.window().days)
    }

    @Test
    fun a_saved_window_survives_a_new_instance() = runTest {
        val store = dataStore("saved")
        DataStoreRetentionSettings(store).save(RetentionWindow.THIRTY_DAYS)

        val reopened = DataStoreRetentionSettings(store)

        assertEquals(RetentionWindow.THIRTY_DAYS, reopened.window())
    }

    @Test
    fun an_unknown_stored_value_falls_back_to_the_default() = runTest {
        val store = dataStore("unknown")
        store.edit { prefs -> prefs[intPreferencesKey("retention_days")] = 9 }

        assertEquals(RetentionWindow.DEFAULT, DataStoreRetentionSettings(store).window())
    }
}
