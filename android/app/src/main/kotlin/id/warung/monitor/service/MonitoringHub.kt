package id.warung.monitor.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.warung.monitor.core.model.TableSummary
import id.warung.monitor.feature.home.domain.MonitoringSample
import id.warung.monitor.feature.home.domain.MonitoringSignal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single owner of the monitoring notification.
 *
 * Home publishes a sample; [MonitoringPolicy] decides whether that is a start,
 * an update, or a stop. "Hentikan" from the notification arrives here too, so
 * the app shell can close the session while the service is already gone.
 */
@Singleton
class MonitoringHub @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MonitoringSignal {

    private val policy = MonitoringPolicy()
    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _active = MutableStateFlow(false)

    /** True while a foreground notification should exist. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Collected by the app shell to close the camera session. */
    val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

    @Synchronized
    override fun publish(sample: MonitoringSample) {
        val command = policy.onStream(sample.stream, sample.summary) ?: return
        // A notification is never allowed to crash the screen it describes:
        // Android 12+ can reject a foreground-service start from the
        // background, and a killed service rejects the intent.
        val applied = runCatching {
            when (command) {
                is MonitoringCommand.Start -> {
                    _active.value = true
                    MonitoringService.start(context, command.summary)
                }
                is MonitoringCommand.Update -> {
                    _active.value = true
                    MonitoringService.update(context, command.summary)
                }
                MonitoringCommand.Stop -> {
                    _active.value = false
                    MonitoringService.stop(context)
                }
            }
        }.isSuccess
        if (!applied) {
            // A stale notification with old totals is worse than none: remove
            // it, forget the attempt, and let the next sample start again.
            runCatching { MonitoringService.stop(context) }
            policy.reset()
            _active.value = false
        }
    }

    /**
     * Removes the notification without asking the shell to close anything.
     * The shell calls this after it has already closed the preview and session.
     */
    @Synchronized
    fun stop() {
        if (!_active.value) return
        policy.reset()
        _active.value = false
        runCatching { MonitoringService.stop(context) }
    }

    /**
     * Notification action "Hentikan": remove the notification now, then let the
     * shell close the stream if it is still alive.
     */
    @Synchronized
    fun requestStop() {
        policy.reset()
        _active.value = false
        runCatching { MonitoringService.stop(context) }
        _stopRequests.tryEmit(Unit)
    }
}