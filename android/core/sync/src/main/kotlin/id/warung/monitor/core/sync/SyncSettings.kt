package id.warung.monitor.core.sync

/**
 * Optional VPS configuration. Sync is not part of login or the local flow: with
 * no base URL the worker exits immediately and the outbox simply keeps its rows
 * (`docs/android-app-plan.md` D2).
 *
 * The interface lives in this JVM module so `feature:settings` can drive it
 * without depending on `app`. Storage and WorkManager stay in `app`.
 */
interface SyncSettings {
    suspend fun endpoint(): SyncEndpoint?

    /** Configured base URL without the token, for showing the saved target. */
    suspend fun configuredBaseUrl(): String?

    /** Whether a token is stored; the value itself never reaches the UI. */
    suspend fun hasToken(): Boolean

    /** Validates HTTPS before writing; an invalid URL must never reach storage. */
    suspend fun saveBaseUrl(baseUrl: String)

    /** Replaces the token; `null` removes it. */
    suspend fun saveToken(token: String?)

    suspend fun clear()
}
