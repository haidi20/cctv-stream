package id.warung.monitor.feature.onboarding.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import id.warung.monitor.feature.onboarding.domain.DeviceProfile
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric is required, not incidental: DataStore picks `Files.move` only
 * when `Build.VERSION.SDK_INT >= 26`. A plain JVM test reports SDK 0 and falls
 * back to `File.renameTo`, which cannot replace an existing file on Windows, so
 * the second write would fail there while working on Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DataStoreOnboardingStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * The target file must not exist yet: DataStore owns its creation. Creating
     * it first with `TemporaryFolder.newFile` would leave an empty file that
     * the serializer treats as corruption.
     */
    private fun dataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "$name.preferences_pb") },
        )

    @Test
    fun an_empty_store_has_no_record() = runTest {
        val store = DataStoreOnboardingStore(dataStore("empty"))

        assertNull(store.load())
    }

    @Test
    fun a_saved_record_round_trips_all_fields() = runTest {
        val store = DataStoreOnboardingStore(dataStore("saved"))
        val record = OnboardingRecord(
            versionCode = 42,
            profile = DeviceProfile.Balanced,
            engineReady = true,
            checkedAtMillis = 99L,
        )

        store.save(record)

        assertEquals(record, store.load())
    }

    @Test
    fun a_second_save_replaces_the_first_record() = runTest {
        val store = DataStoreOnboardingStore(dataStore("replace"))
        store.save(OnboardingRecord(1, DeviceProfile.Full, true, 1L))

        store.save(OnboardingRecord(2, DeviceProfile.Lite, false, 2L))

        assertEquals(2L, store.load()?.versionCode)
        assertEquals(DeviceProfile.Lite, store.load()?.profile)
    }
}