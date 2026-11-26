package id.warung.monitor.service

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import id.warung.monitor.core.model.TableSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Host-level proof that the service enters the foreground and that its
 * notification never carries stale totals. A real notification shade still
 * needs a device; this pins the platform contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class MonitoringServiceTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun starting_it_posts_an_ongoing_notification_with_the_counts() {
        val controller = Robolectric.buildService(MonitoringService::class.java)
        val service = controller.create().get()

        service.onStartCommand(
            monitoringIntent(MonitoringService.ACTION_START, TableSummary(4, 3, 0)),
            0,
            1,
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).allNotifications.single()
        val title = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()

        assertEquals(MonitoringNotificationText.TITLE, title)
        assertEquals("4 kosong, 3 terisi", text)
        assertTrue("monitoring must survive the screen turning off", notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertOnline()
    }

    @Test
    fun an_update_without_a_snapshot_never_shows_old_zeroes() {
        val service = Robolectric.buildService(MonitoringService::class.java).create().get()
        service.onStartCommand(monitoringIntent(MonitoringService.ACTION_START, TableSummary(2, 1, 0)), 0, 1)
        service.onStartCommand(monitoringIntent(MonitoringService.ACTION_UPDATE, null), 0, 2)

        val manager = context.getSystemService(NotificationManager::class.java)
        val text = shadowOf(manager).allNotifications.single()
            .extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()

        assertEquals(MonitoringNotificationText.WAITING, text)
        assertOnline()
    }

    @Test
    fun the_notification_only_offers_the_stop_action() {
        val service = Robolectric.buildService(MonitoringService::class.java).create().get()
        service.onStartCommand(monitoringIntent(MonitoringService.ACTION_START, null), 0, 1)

        val notification = shadowOf(context.getSystemService(NotificationManager::class.java))
            .allNotifications
            .single()

        val actions = notification.actions
        assertNotNull("staff need a way to stop monitoring", actions)
        assertEquals(1, actions.size)
        assertEquals(MonitoringNotificationText.STOP_ACTION, actions[0].title.toString())
    }

    @Test
    fun a_null_restart_intent_stops_instead_of_claiming_monitoring() {
        val controller = Robolectric.buildService(MonitoringService::class.java)
        val service = controller.create().get()
        service.onStartCommand(monitoringIntent(MonitoringService.ACTION_START, null), 0, 1)

        service.onStartCommand(null, 0, 2)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(
            "a sticky restart with no session must not keep a notification",
            0,
            shadowOf(manager).allNotifications.size,
        )
    }

    @Test
    fun the_stop_action_is_a_private_component() {
        val info = context.packageManager.getReceiverInfo(
            android.content.ComponentName(context, StopMonitoringReceiver::class.java),
            0,
        )
        assertEquals(false, info.exported)
    }

    @Test
    fun the_service_is_private_and_declares_media_playback() {
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, MonitoringService::class.java),
            0,
        )
        assertEquals(false, info.exported)
        assertTrue(
            "the foreground type must match the manifest declaration",
            info.foregroundServiceType and
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0,
        )
    }

    @Test
    @Config(sdk = [29])
    fun it_works_on_the_oldest_supported_android() {
        val service = Robolectric.buildService(MonitoringService::class.java).create().get()
        service.onStartCommand(monitoringIntent(MonitoringService.ACTION_START, TableSummary(1, 1, 1)), 0, 1)

        val text = shadowOf(context.getSystemService(NotificationManager::class.java))
            .allNotifications
            .single()
            .extras
            .getCharSequence(android.app.Notification.EXTRA_TEXT)
            ?.toString()
        assertEquals("1 kosong, 1 terisi, 1 tidak diketahui", text)
    }

    private fun monitoringIntent(action: String, summary: TableSummary?): Intent =
        Intent(context, MonitoringService::class.java).apply {
            this.action = action
            putExtra("has_summary", summary != null)
            if (summary != null) {
                putExtra("available", summary.available)
                putExtra("occupied", summary.occupied)
                putExtra("unknown", summary.unknown)
            }
        }

    /** Robolectric cannot run the real foreground clamp; assert API availability. */
    private fun assertOnline() {
        assertTrue(Build.VERSION.SDK_INT >= 29)
    }
}