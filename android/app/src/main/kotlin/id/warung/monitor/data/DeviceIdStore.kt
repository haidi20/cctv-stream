package id.warung.monitor.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.deviceStore by preferencesDataStore(name = "device")

/**
 * Stable per-install identifier used as `device_id` in sync payloads.
 *
 * It lives in DataStore, not `SharedPreferences`, so concurrent reads cannot
 * race a half-written value. The id contains only characters the schema accepts,
 * and it never encodes a person or a phone number.
 */
@Singleton
class DeviceIdStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DeviceIdSource {

    override suspend fun deviceId(): String {
        val existing = context.deviceStore.data.first()[KEY]
        if (!existing.isNullOrBlank()) return existing
        val generated = newId()
        context.deviceStore.edit { preferences ->
            // `edit` is atomic; if another writer won, keep the stored value so
            // two concurrent callers cannot disagree about the device id.
            preferences[KEY] = preferences[KEY] ?: generated
        }
        return context.deviceStore.data.first()[KEY] ?: generated
    }

    private fun newId(): String =
        "android-" + UUID.randomUUID().toString().replace("-", "").take(24)

    private companion object {
        val KEY = stringPreferencesKey("device_id")
    }
}
