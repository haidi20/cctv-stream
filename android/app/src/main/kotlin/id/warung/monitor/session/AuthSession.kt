package id.warung.monitor.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Always-unlocked session. The PIN flow is disabled in every build: the app
 * opens Home directly and never returns to a login screen, including after
 * logout or a long stay in the background.
 */
@Singleton
class AuthSession @Inject constructor() {

    private val _unlocked = MutableStateFlow(true)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun unlock() {
        _unlocked.value = true
    }

    fun lock() = Unit

    fun onBackground(nowMs: Long) = Unit

    fun onForeground(nowMs: Long, lockAfterMs: Long = LOCK_AFTER_BACKGROUND_MS) = Unit

    companion object {
        const val LOCK_AFTER_BACKGROUND_MS: Long = 5 * 60_000L
    }
}