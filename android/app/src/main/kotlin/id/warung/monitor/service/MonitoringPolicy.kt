package id.warung.monitor.service

import id.warung.monitor.core.model.TableSummary
import id.warung.monitor.feature.home.domain.StreamUiState

/** What the app shell must do with the foreground service. */
sealed interface MonitoringCommand {
    /** Start (or keep running) with no counts yet; never shows zeroes. */
    data class Start(val summary: TableSummary?) : MonitoringCommand

    /** Refresh the notification text; [summary] is `null` when counts are stale. */
    data class Update(val summary: TableSummary?) : MonitoringCommand

    /** The user closed the stream: stop the service and remove the notification. */
    data object Stop : MonitoringCommand
}

/**
 * Pure state machine for the monitoring notification.
 *
 * Rules that keep the notification honest:
 * - Idle before any camera was opened does nothing.
 * - A failed or reconnecting stream publishes a summary-less update, so an old
 *   count can never outlive the video.
 * - An unchanged snapshot does not republish, so a 1 s poll does not spam the
 *   notification shade.
 */
class MonitoringPolicy {

    private var running = false
    private var lastSummary: TableSummary? = null

    fun onStream(stream: StreamUiState, summary: TableSummary?): MonitoringCommand? {
        if (stream is StreamUiState.Idle) {
            if (!running) return null
            running = false
            lastSummary = null
            return MonitoringCommand.Stop
        }

        val live = stream is StreamUiState.Live
        val published = if (live) summary else null
        if (!running) {
            running = true
            lastSummary = published
            return MonitoringCommand.Start(published)
        }
        if (published == lastSummary) return null
        lastSummary = published
        return MonitoringCommand.Update(published)
    }

    /**
     * Clears the session memory so the next stream starts the service again.
     * The caller is responsible for actually stopping a running service.
     */
    fun reset() {
        running = false
        lastSummary = null
    }
}