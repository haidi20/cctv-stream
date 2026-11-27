package id.warung.monitor.feature.settings.domain

import id.warung.monitor.core.sync.RetentionPolicy
import id.warung.monitor.core.sync.RetentionWindow

/**
 * State of the "Penyimpanan & Riwayat" screen (`docs/android-sync.md`
 * section 7).
 *
 * [savedWindow] is what storage holds and [window] is what the user picked, so
 * the save button can stay disabled until the choice actually differs. The
 * screen never deletes: saving only changes the window, and the next prune pass
 * applies it.
 */
data class RetentionState(
    val loading: Boolean = true,
    val window: RetentionWindow = RetentionWindow.DEFAULT,
    val savedWindow: RetentionWindow = RetentionWindow.DEFAULT,
    val rows: Int = 0,
    val databaseBytes: Long = 0,
    val rowLimit: Int = RetentionPolicy.MAX_ROWS,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val hasChange: Boolean get() = window != savedWindow

    val canSave: Boolean get() = !loading && !saving && hasChange

    /** Local history disappears sooner than the VPS copy, so it is flagged. */
    val showsShortRetentionWarning: Boolean get() = window == RetentionWindow.THREE_DAYS

    /** The row cap is the other bound; hitting it means age pruning is behind. */
    val atRowLimit: Boolean get() = rows >= rowLimit
}
