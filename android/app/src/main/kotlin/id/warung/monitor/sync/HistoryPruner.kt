package id.warung.monitor.sync

import id.warung.monitor.core.sync.RetentionSettings
import id.warung.monitor.data.TableHistoryPruner

/**
 * One prune pass: read the user's window, then delete only what the policy
 * allows.
 *
 * Kept free of WorkManager so the ordering is testable on a plain JVM: the
 * window is read *before* the delete, so a value changed while the worker was
 * queued is honoured instead of the value that was current when it was queued.
 */
class HistoryPruner(
    private val settings: RetentionSettings,
    private val history: TableHistoryPruner,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Returns the number of history rows removed; 0 is a normal outcome. */
    suspend fun run(): Int {
        val window = settings.window()
        return history.prune(nowMillis = clock(), window = window)
    }
}
