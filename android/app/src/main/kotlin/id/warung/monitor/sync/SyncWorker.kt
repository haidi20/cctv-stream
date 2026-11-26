package id.warung.monitor.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import id.warung.monitor.core.sync.SyncSettings

/**
 * One bounded drain of the outbox.
 *
 * `docs/android-sync.md` section 8 makes this a `OneTimeWorkRequest` with
 * `NetworkType.CONNECTED`, not a periodic worker. Retry uses `Result.retry()`
 * and WorkManager's own backoff, which follows the same 30 s -> 15 min ladder
 * via [SyncScheduler].
 *
 * No RTSP URL, token, or payload is logged. The only failure text surfaced is
 * the stable reason string from [SyncCoordinator], never an exception message.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: SyncRunner,
    private val settings: SyncSettings,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (settings.endpoint() == null) return Result.success()

        return when (val decision = runner.run()) {
            is SyncRunner.Decision.Done -> Result.success()
            is SyncRunner.Decision.RunAgain -> {
                // More rows remain beyond the bounded batch count; one more
                // run drains them without waiting for the next transition.
                SyncScheduler.enqueueNow(applicationContext)
                Result.success()
            }

            is SyncRunner.Decision.Retry -> {
                // Use the documented ladder, not WorkManager's 10 s -> 5 h.
                SyncScheduler.enqueueAfter(applicationContext, decision.delayMillis)
                Result.success()
            }
            is SyncRunner.Decision.Stop -> Result.failure()
        }
    }

    companion object {
        const val NAME: String = "outbox-sync"
    }
}

