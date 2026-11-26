package id.warung.monitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import id.warung.monitor.feature.cameras.session.CameraSession
import id.warung.monitor.feature.home.domain.CameraPreviewCoordinator
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the "Hentikan" action from the monitoring notification.
 *
 * Not exported: the intent is built with an explicit component and its only
 * sender is a `PendingIntent` owned by this app.
 *
 * The receiver closes the stream itself instead of waiting for the UI. With
 * the screen off there may be no composition left to collect a stop event, and
 * a player running without a notification is invisible to the user.
 */
@AndroidEntryPoint
class StopMonitoringReceiver : BroadcastReceiver() {

    @Inject
    lateinit var hub: MonitoringHub

    @Inject
    lateinit var preview: CameraPreviewCoordinator

    @Inject
    lateinit var session: CameraSession

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MonitoringService.ACTION_STOP) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                hub.requestStop()
                runCatching { preview.close() }
                runCatching { session.close() }
            } finally {
                pending.finish()
            }
        }
    }
}