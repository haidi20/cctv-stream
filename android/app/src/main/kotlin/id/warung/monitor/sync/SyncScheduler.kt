package id.warung.monitor.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues the single sync worker.
 *
 * `KEEP` is deliberate: every table transition would otherwise cancel and
 * recreate the request, and a burst of 20 transitions would never let one
 * attempt start. WorkManager's own exponential backoff (10 s -> 5 h) does not
 * match the documented 30 s/1 m/5 m/15 m ladder in `docs/monitoring.md` 4.3, so
 * [SyncRunner] re-enqueues with the exact delay via [enqueueAfter].
 *
 * Follow-up requests use `APPEND_OR_REPLACE`, never `KEEP`: when the worker
 * asks for another run it is still RUNNING, and `KEEP` would treat the running
 * request as the one to keep and silently drop the follow-up.
 */
object SyncScheduler {

    /** Debounce so a burst of transitions becomes one request. */
    val DEBOUNCE_MILLIS: Long = 30_000L

    /** WorkManager rejects a non-positive delay; 1 ms would busy-loop offline. */
    val MIN_DELAY_MILLIS: Long = 10_000L

    /**
     * The queue decision for one enqueue, kept separate from WorkManager so
     * `SyncSchedulerTest` can assert it without a live `WorkManager`. WorkInfo
     * does not expose the initial delay, so a Robolectric test could not check
     * the clamp anyway.
     */
    internal data class Plan(val policy: ExistingWorkPolicy, val delayMillis: Long)

    internal fun planDebounced(): Plan = Plan(ExistingWorkPolicy.KEEP, DEBOUNCE_MILLIS)

    internal fun planImmediate(): Plan = Plan(ExistingWorkPolicy.APPEND_OR_REPLACE, 0L)

    internal fun planRetry(delayMillis: Long): Plan = Plan(
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        delayMillis.coerceAtLeast(MIN_DELAY_MILLIS),
    )

    internal fun planRestart(): Plan = Plan(ExistingWorkPolicy.REPLACE, 0L)

    fun enqueue(context: Context) {
        enqueue(context, planDebounced())
    }

    fun enqueueNow(context: Context) {
        // The caller is usually the running worker itself; appending puts the
        // next drain right behind it instead of being ignored.
        enqueue(context, planImmediate())
    }

    /** Re-enqueues with the exact ladder delay after a retryable failure. */
    fun enqueueAfter(context: Context, delayMillis: Long) {
        // Also called from inside the worker. REPLACE would cancel the work
        // that is currently executing and discard its result.
        enqueue(context, planRetry(delayMillis))
    }

    /**
     * User-initiated "sync now" from settings.
     *
     * Unlike [enqueueNow], this replaces the chain: a pending 15-minute retry
     * from an old token must not delay the drain after the user fixes it.
     */
    fun restart(context: Context) {
        enqueue(context, planRestart())
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.NAME)
    }

    private fun enqueue(context: Context, plan: Plan) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.NAME,
            plan.policy,
            request(initialDelayMillis = plan.delayMillis),
        )
    }

    private fun request(initialDelayMillis: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .build()
}

