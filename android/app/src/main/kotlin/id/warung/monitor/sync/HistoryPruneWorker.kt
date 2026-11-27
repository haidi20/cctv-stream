package id.warung.monitor.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * One bounded prune of `table_logs` (`docs/android-sync.md` sections 6 and 8).
 *
 * Daily and periodic, unlike [SyncWorker]: pruning needs no network and must
 * keep running on a device that never reaches the server. A failure returns
 * `retry` instead of `failure` so a transient SQLite lock cannot leave storage
 * growing until the next install; WorkManager's own backoff is acceptable here
 * because a late prune is harmless.
 */
@HiltWorker
class HistoryPruneWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pruner: HistoryPruner,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        pruner.run()
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        Result.retry()
    }

    companion object {
        const val NAME: String = "history-prune"
    }
}
