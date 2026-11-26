package id.warung.monitor.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One heartbeat, then schedules the next one.
 *
 * Unlike [SyncWorker] this never calls `Result.retry()`: the retry cadence is
 * explicit in [HeartbeatScheduler] so the 30 s/60 s contract is honoured rather
 * than WorkManager's 10 s -> 5 h backoff. A rejection stops the chain until the
 * user saves new credentials.
 */
@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: HeartbeatRunner,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (val decision = runner.run()) {
        HeartbeatRunner.Decision.SentLive -> {
            HeartbeatScheduler.enqueueAfter(applicationContext, HeartbeatScheduler.LIVE_INTERVAL_MILLIS)
            Result.success()
        }

        HeartbeatRunner.Decision.SentIdle -> {
            HeartbeatScheduler.enqueueAfter(applicationContext, HeartbeatScheduler.IDLE_INTERVAL_MILLIS)
            Result.success()
        }

        HeartbeatRunner.Decision.NotConfigured -> Result.success()

        is HeartbeatRunner.Decision.Retry -> {
            HeartbeatScheduler.enqueueAfter(applicationContext, decision.delayMillis)
            Result.success()
        }

        is HeartbeatRunner.Decision.Stop -> Result.failure()
    }

    companion object {
        const val NAME: String = "device-heartbeat"
    }
}
