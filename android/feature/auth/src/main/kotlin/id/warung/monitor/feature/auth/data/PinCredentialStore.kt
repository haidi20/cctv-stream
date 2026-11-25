package id.warung.monitor.feature.auth.data

import id.warung.monitor.feature.auth.domain.PinCredential

/**
 * Everything the login flow persists locally.
 *
 * Deliberately narrow: no PIN, no plaintext, no session. The session is
 * memory-only by design, so process death always returns to the lock screen.
 */
interface AuthStore {
    suspend fun credential(): PinCredential?
    suspend fun saveCredential(credential: PinCredential)
    suspend fun isSetupComplete(): Boolean
    suspend fun markSetupComplete()
    suspend fun failures(): Int
    suspend fun lockedUntilElapsedMs(): Long?
    suspend fun recordFailure(failures: Int, lockedUntilElapsedMs: Long?)
    suspend fun resetFailures()
    suspend fun clearAll()
}

/** Deterministic store for JVM tests and previews. */
class InMemoryAuthStore(
    credential: PinCredential? = null,
    setupComplete: Boolean = credential != null,
    failures: Int = 0,
    lockedUntilElapsedMs: Long? = null,
) : AuthStore {

    private var storedCredential: PinCredential? = credential
    private var setupComplete: Boolean = setupComplete
    private var failureCount: Int = failures
    private var lockDeadline: Long? = lockedUntilElapsedMs

    override suspend fun credential(): PinCredential? = storedCredential

    override suspend fun saveCredential(credential: PinCredential) {
        storedCredential = credential
    }

    override suspend fun isSetupComplete(): Boolean = setupComplete

    override suspend fun markSetupComplete() {
        setupComplete = true
    }

    override suspend fun failures(): Int = failureCount

    override suspend fun lockedUntilElapsedMs(): Long? = lockDeadline

    override suspend fun recordFailure(failures: Int, lockedUntilElapsedMs: Long?) {
        failureCount = failures
        lockDeadline = lockedUntilElapsedMs
    }

    override suspend fun resetFailures() {
        failureCount = 0
        lockDeadline = null
    }

    override suspend fun clearAll() {
        storedCredential = null
        setupComplete = false
        failureCount = 0
        lockDeadline = null
    }
}