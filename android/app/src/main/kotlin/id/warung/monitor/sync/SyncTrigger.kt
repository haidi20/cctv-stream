package id.warung.monitor.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks WorkManager for a debounced sync after a row is durable.
 *
 * It does **not** latch: a unique work request with `KEEP` already coalesces a
 * burst, and suppressing later calls would strand rows written after a worker
 * finished. Failures are swallowed because sync is optional; the outbox keeps
 * the rows and the next app start or transition retries.
 */
@Singleton
class SyncTrigger @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun requestSync() {
        runCatching { SyncScheduler.enqueue(context) }
    }
}

