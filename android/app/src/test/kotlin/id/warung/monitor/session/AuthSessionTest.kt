package id.warung.monitor.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionTest {

    @Test
    fun a_dev_build_starts_unlocked_without_a_pin() {
        val session = AuthSession()
        session.enableDevMode()

        assertTrue(session.unlocked.value)

        session.lock()
        assertTrue("dev mode must stay usable after logout", session.unlocked.value)
    }

    @Test
    fun a_production_build_still_starts_locked() {
        val session = AuthSession()

        assertFalse(session.unlocked.value)
    }

    @Test
    fun unlock_then_lock_toggles_the_session() {
        val session = AuthSession()
        assertFalse(session.unlocked.value)

        session.unlock()
        assertTrue(session.unlocked.value)

        session.lock()
        assertFalse(session.unlocked.value)
    }

    @Test
    fun a_short_stay_in_the_background_keeps_the_session_unlocked() {
        val session = AuthSession()
        session.unlock()

        session.onBackground(1_000L)
        session.onForeground(1_000L + AuthSession.LOCK_AFTER_BACKGROUND_MS - 1)

        assertTrue(session.unlocked.value)
    }

    @Test
    fun staying_in_the_background_for_the_full_window_locks_the_session() {
        val session = AuthSession()
        session.unlock()

        session.onBackground(1_000L)
        session.onForeground(1_000L + AuthSession.LOCK_AFTER_BACKGROUND_MS)

        assertFalse(session.unlocked.value)
    }

    @Test
    fun repeated_background_events_keep_the_earliest_timestamp() {
        val session = AuthSession()
        session.unlock()

        session.onBackground(1_000L)
        session.onBackground(200_000L)
        session.onForeground(1_000L + AuthSession.LOCK_AFTER_BACKGROUND_MS)

        assertFalse(session.unlocked.value)
    }

    @Test
    fun a_locked_session_is_not_affected_by_lifecycle_events() {
        val session = AuthSession()

        session.onBackground(1_000L)
        session.onForeground(999_999_999L)
        assertFalse(session.unlocked.value)

        session.unlock()
        assertTrue(session.unlocked.value)
    }

    @Test
    fun lock_after_a_background_callback_resets_the_window() {
        val session = AuthSession()
        session.unlock()
        session.onBackground(1_000L)

        session.lock()
        session.unlock()
        session.onForeground(999_999_999L)

        assertTrue(session.unlocked.value)
    }
}