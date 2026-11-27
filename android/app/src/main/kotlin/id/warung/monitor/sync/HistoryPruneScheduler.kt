package id.warung.monitor.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the daily `table_logs` prune (`docs/android-sync.md` section 8).
 *
 * The periodic request is the long-lived owner: it is created once with `KEEP`
 * so app starts never reset the interval, and updated with `UPDATE` only when
 * the user saves a new retention window. A one-time request runs at app start so
 * a device that was closed for a week prunes before the next daily tick.
 */
object HistoryPruneScheduler {

    /** Daily, per `android-sync.md` section 8; Android's minimum is 15 minutes. */
    const val INTERVAL_DAYS: Long = 1L

    const val NOW_NAME: String = "history-prune-now"

    /** Creates the periodic request; an existing one keeps its schedule. */
    fun ensureScheduled(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HistoryPruneWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest(),
        )
    }

    /** Applies a new retention window to the next scheduled run. */
    fun reschedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HistoryPruneWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest(),
        )
    }

    /** Start-up pass; bounded and idempotent, so `REPLACE` is safe. */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HistoryPruneWorker>().build(),
        )
    }

    private fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<HistoryPruneWorker>(INTERVAL_DAYS, TimeUnit.DAYS).build()
}
