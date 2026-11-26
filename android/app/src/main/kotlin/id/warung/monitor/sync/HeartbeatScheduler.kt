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
 * Self-rescheduling heartbeat chain.
 *
 * `PeriodicWorkRequest` is not used: Android floors its interval at 15 minutes,
 * while the contract wants 30-60 s (`docs/android-sync.md` section 8). Each run
 * enqueues the next one, and [stop] cancels the chain when monitoring ends.
 *
 * `APPEND_OR_REPLACE`, never `KEEP`: the next request is usually enqueued by
 * the worker that is still RUNNING, and `KEEP` would drop it.
 */
object HeartbeatScheduler {

    val LIVE_INTERVAL_MILLIS: Long = 30_000L
    val IDLE_INTERVAL_MILLIS: Long = 60_000L

    /** WorkManager rejects a non-positive delay; a tiny one would busy-loop. */
    val MIN_DELAY_MILLIS: Long = 10_000L

    internal data class Plan(val policy: ExistingWorkPolicy, val delayMillis: Long)

    internal fun planFirst(): Plan = Plan(ExistingWorkPolicy.REPLACE, LIVE_INTERVAL_MILLIS)

    internal fun planNext(delayMillis: Long): Plan = Plan(
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        delayMillis.coerceAtLeast(MIN_DELAY_MILLIS),
    )

    fun start(context: Context) {
        enqueue(context, planFirst())
    }

    fun enqueueAfter(context: Context, delayMillis: Long) {
        enqueue(context, planNext(delayMillis))
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(HeartbeatWorker.NAME)
    }

    private fun enqueue(context: Context, plan: Plan) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            HeartbeatWorker.NAME,
            plan.policy,
            request(plan.delayMillis),
        )
    }

    private fun request(initialDelayMillis: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .build()
}
