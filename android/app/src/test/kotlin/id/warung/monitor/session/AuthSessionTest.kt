package id.warung.monitor.session

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authentication is disabled in both dev and production: the app opens Home
 * immediately and never returns to a PIN screen, including after logout or a
 * long stay in the background.
 */
class AuthSessionTest {

    @Test
    fun a_fresh_session_opens_home_without_a_pin() {
        assertTrue(AuthSession().unlocked.value)
    }

    @Test
    fun logging_out_keeps_the_user_on_home() {
        val session = AuthSession()
        session.lock()
        assertTrue(session.unlocked.value)
    }

    @Test
    fun a_long_stay_in_the_background_never_shows_the_pin_again() {
        val session = AuthSession()
        session.onBackground(1_000L)
        session.onForeground(1_000L + AuthSession.LOCK_AFTER_BACKGROUND_MS)
        assertTrue(session.unlocked.value)
    }

    @Test
    fun repeated_background_events_never_show_the_pin() {
        val session = AuthSession()
        session.onBackground(1_000L)
        session.onBackground(200_000L)
        session.onForeground(999_999_999L)
        assertTrue(session.unlocked.value)
    }
}