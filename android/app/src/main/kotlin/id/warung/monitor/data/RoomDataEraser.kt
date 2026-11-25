package id.warung.monitor.data

import id.warung.monitor.core.database.MonitorDatabase
import id.warung.monitor.feature.auth.data.AuthStore
import id.warung.monitor.feature.auth.di.AppDataEraser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Lupa PIN" reset: removes the credential and every local row.
 *
 * The database is cleared in one transaction before the PIN, so a crash midway
 * cannot leave a re-lockable app with half-deleted user data. Keystore secrets
 * must be added here when the camera credentials land.
 */
@Singleton
class RoomDataEraser @Inject constructor(
    private val authStore: AuthStore,
    private val database: MonitorDatabase,
) : AppDataEraser {

    override suspend fun eraseAll() = withContext(Dispatchers.IO) {
        database.clearAllTables()
        authStore.clearAll()
    }
}