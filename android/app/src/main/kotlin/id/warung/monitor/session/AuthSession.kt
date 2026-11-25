package id.warung.monitor.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory unlock state, shared by every activity instance.
 *
 * Deliberately not persisted and not saved in `rememberSaveable`: rotation keeps
 * the user signed in because the singleton survives the activity re-create, but
 * process death always returns to the PIN screen.
 */
@Singleton
class AuthSession @Inject constructor() {

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private var backgroundedAtMs: Long? = null

    fun unlock() {
        _unlocked.value = true
        backgroundedAtMs = null
    }

    fun lock() {
        _unlocked.value = false
        backgroundedAtMs = null
    }

    /** Starts the auto-lock window; repeated stops keep the earliest time. */
    fun onBackground(nowMs: Long) {
        if (!_unlocked.value) return
        if (backgroundedAtMs == null) backgroundedAtMs = nowMs
    }

    /** Locks when the app stayed in the background for at least [lockAfterMs]. */
    fun onForeground(nowMs: Long, lockAfterMs: Long = LOCK_AFTER_BACKGROUND_MS) {
        val since = backgroundedAtMs ?: return
        backgroundedAtMs = null
        if (_unlocked.value && nowMs - since >= lockAfterMs) {
            _unlocked.value = false
        }
    }

    companion object {
        /** Product default: 5 minutes in the background (android-app-plan 8.4). */
        const val LOCK_AFTER_BACKGROUND_MS: Long = 5 * 60_000L
    }
}