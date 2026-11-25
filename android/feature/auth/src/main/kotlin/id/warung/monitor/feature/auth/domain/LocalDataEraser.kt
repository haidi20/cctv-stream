package id.warung.monitor.feature.auth.domain

/**
 * Full local reset: PIN, camera profiles, table maps, and future secrets.
 *
 * Kept behind a seam so the ViewModel never touches Android storage directly.
 */
interface LocalDataEraser {
    suspend fun eraseAll()
}