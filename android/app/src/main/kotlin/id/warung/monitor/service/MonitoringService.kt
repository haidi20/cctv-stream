package id.warung.monitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import id.warung.monitor.MainActivity
import id.warung.monitor.R
import id.warung.monitor.core.model.TableSummary

/**
 * Keeps the monitoring session alive while the screen is off.
 *
 * The service only renders a notification: detection, counts, and the video
 * stay where they already were. Totals are passed as primitives so a process
 * restart cannot resurrect a stale object graph.
 *
 * Restart policy: `START_STICKY`, but a null restart intent stops the service.
 * The auth session is memory-only, so after process death nothing is streaming;
 * a restarted notification would claim monitoring that is not happening.
 */
class MonitoringService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    /** Never bound: the only clients are the notification and the app shell. */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_UPDATE -> {
                val summary = intent.readSummary()
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(summary),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                return START_STICKY
            }
            ACTION_STOP, null -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pemantauan CCTV",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Menampilkan status pemantauan meja saat layar mati"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(summary: TableSummary?): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            this,
            REQUEST_STOP,
            Intent(this, StopMonitoringReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(MonitoringNotificationText.TITLE)
            .setContentText(MonitoringNotificationText.counts(summary))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // No camera address, IP, or credential ever enters this notification.
            .addAction(0, MonitoringNotificationText.STOP_ACTION, stop)
            .build()
    }

    private fun Intent.readSummary(): TableSummary? {
        if (!getBooleanExtra(EXTRA_HAS_SUMMARY, false)) return null
        return TableSummary(
            available = getIntExtra(EXTRA_AVAILABLE, 0),
            occupied = getIntExtra(EXTRA_OCCUPIED, 0),
            unknown = getIntExtra(EXTRA_UNKNOWN, 0),
        )
    }

    companion object {
        const val ACTION_START: String = "id.warung.monitor.action.START_MONITORING"
        const val ACTION_UPDATE: String = "id.warung.monitor.action.UPDATE_MONITORING"
        const val ACTION_STOP: String = "id.warung.monitor.action.STOP_MONITORING"
        const val CHANNEL_ID: String = "stream"
        const val NOTIFICATION_ID: Int = 1001

        private const val REQUEST_OPEN: Int = 1
        private const val REQUEST_STOP: Int = 2
        private const val EXTRA_HAS_SUMMARY: String = "has_summary"
        private const val EXTRA_AVAILABLE: String = "available"
        private const val EXTRA_OCCUPIED: String = "occupied"
        private const val EXTRA_UNKNOWN: String = "unknown"

        /** First start: allowed to promote the process to foreground. */
        fun start(context: Context, summary: TableSummary?) {
            val intent = intent(context, ACTION_START, summary)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Later updates must not call `startForegroundService` again: Android 12
         * rejects a second foreground-service start from the background, which
         * is exactly the situation (screen off) this notification exists for.
         * The service is already running, so a plain `startService` delivers the
         * new intent.
         */
        fun update(context: Context, summary: TableSummary?) {
            val intent = intent(context, ACTION_UPDATE, summary)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }

        private fun intent(context: Context, action: String, summary: TableSummary?): Intent =
            Intent(context, MonitoringService::class.java).apply {
                this.action = action
                putExtra(EXTRA_HAS_SUMMARY, summary != null)
                if (summary != null) {
                    putExtra(EXTRA_AVAILABLE, summary.available)
                    putExtra(EXTRA_OCCUPIED, summary.occupied)
                    putExtra(EXTRA_UNKNOWN, summary.unknown)
                }
            }
    }
}