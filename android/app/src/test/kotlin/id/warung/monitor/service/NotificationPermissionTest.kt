package id.warung.monitor.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `POST_NOTIFICATIONS` only exists from Android 13, and asking after the user
 * already granted it would look like a bug. The prompt is also never a gate:
 * monitoring still runs when the permission is denied.
 */
class NotificationPermissionTest {

    @Test
    fun it_never_asks_before_android_13() {
        assertFalse(NotificationPermission.shouldRequest(sdkInt = 32, granted = false))
    }

    @Test
    fun it_asks_once_on_android_13_when_missing() {
        assertTrue(NotificationPermission.shouldRequest(sdkInt = 33, granted = false))
    }

    @Test
    fun it_does_not_ask_again_after_a_grant() {
        assertFalse(NotificationPermission.shouldRequest(sdkInt = 33, granted = true))
    }
}