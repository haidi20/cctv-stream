package id.warung.monitor.feature.onboarding.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import id.warung.monitor.feature.onboarding.domain.DeviceProfile
import kotlinx.coroutines.flow.first

/** Persisted outcome of the last check, so it is not re-run on every launch. */
data class OnboardingRecord(
    val versionCode: Long,
    val profile: DeviceProfile,
    val engineReady: Boolean,
    val checkedAtMillis: Long,
)

/**
 * Stores the last diagnostic outcome.
 *
 * A record counts as current only for the APK version that produced it: after
 * an update the check runs again (`device-compatibility.md` section 7).
 */
interface OnboardingStore {
    suspend fun load(): OnboardingRecord?

    suspend fun save(record: OnboardingRecord)
}

class DataStoreOnboardingStore(
    private val dataStore: DataStore<Preferences>,
) : OnboardingStore {

    override suspend fun load(): OnboardingRecord? {
        val prefs = dataStore.data.first()
        val versionCode = prefs[KEY_VERSION_CODE] ?: return null
        val profile = prefs[KEY_PROFILE]?.let { stored ->
            DeviceProfile.entries.firstOrNull { it.name == stored }
        } ?: return null
        return OnboardingRecord(
            versionCode = versionCode,
            profile = profile,
            engineReady = prefs[KEY_ENGINE_READY] == true,
            checkedAtMillis = prefs[KEY_CHECKED_AT] ?: 0L,
        )
    }

    override suspend fun save(record: OnboardingRecord) {
        dataStore.edit { prefs ->
            prefs[KEY_VERSION_CODE] = record.versionCode
            prefs[KEY_PROFILE] = record.profile.name
            prefs[KEY_ENGINE_READY] = record.engineReady
            prefs[KEY_CHECKED_AT] = record.checkedAtMillis
        }
    }

    private companion object {
        val KEY_VERSION_CODE = longPreferencesKey("onboarding_version_code")
        val KEY_PROFILE = stringPreferencesKey("onboarding_profile")
        val KEY_ENGINE_READY = booleanPreferencesKey("onboarding_engine_ready")
        val KEY_CHECKED_AT = longPreferencesKey("onboarding_checked_at")
    }
}

/** One DataStore per process; the property delegate enforces that. */
val Context.onboardingDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "onboarding")