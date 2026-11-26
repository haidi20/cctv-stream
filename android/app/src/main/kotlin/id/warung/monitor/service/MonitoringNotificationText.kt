package id.warung.monitor.service

import id.warung.monitor.core.model.TableSummary

/**
 * Notification copy for the foreground service.
 *
 * Totals come from Rust. A missing snapshot becomes "menunggu" instead of zero,
 * and unknown tables are always called out so a camera problem is not mistaken
 * for an empty room (`AGENTS.md` rule 5). Nothing here may include a camera
 * address, IP, or credential.
 */
object MonitoringNotificationText {

    const val TITLE: String = "Memantau CCTV warung"
    const val WAITING: String = "Menunggu hitungan meja"
    const val STOP_ACTION: String = "Hentikan"
    const val OPEN_ACTION: String = "Buka"

    fun counts(summary: TableSummary?): String {
        if (summary == null) return WAITING
        val base = "${summary.available} kosong, ${summary.occupied} terisi"
        return if (summary.unknown > 0) {
            "$base, ${summary.unknown} tidak diketahui"
        } else {
            base
        }
    }
}