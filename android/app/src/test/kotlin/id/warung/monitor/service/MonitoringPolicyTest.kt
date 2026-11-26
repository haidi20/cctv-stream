package id.warung.monitor.service

import id.warung.monitor.core.model.TableSummary
import id.warung.monitor.feature.home.domain.StreamErrorKind
import id.warung.monitor.feature.home.domain.StreamUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The foreground service must follow the stream, not a timer.
 *
 * A notification that keeps saying "3 kosong, 2 terisi" after the camera died is
 * worse than no notification at all: staff would seat guests at tables that are
 * actually occupied. Every non-live state therefore publishes a summary-less
 * update, and an idle stream stops the service.
 */
class MonitoringPolicyTest {

    private val summary = TableSummary(available = 3, occupied = 2, unknown = 1)

    @Test
    fun nothing_happens_before_a_camera_is_opened() {
        val policy = MonitoringPolicy()

        assertNull(policy.onStream(StreamUiState.Idle, summary = null))
    }

    @Test
    fun connecting_starts_the_service_without_inventing_counts() {
        val policy = MonitoringPolicy()

        assertEquals(
            MonitoringCommand.Start(summary = null),
            policy.onStream(StreamUiState.Connecting, summary = null),
        )
    }

    @Test
    fun live_with_the_same_snapshot_does_not_republish() {
        val policy = MonitoringPolicy()
        policy.onStream(StreamUiState.Connecting, summary = null)
        assertEquals(MonitoringCommand.Update(summary), policy.onStream(StreamUiState.Live(1L), summary))

        assertNull(
            "an unchanged poll tick must not notify again",
            policy.onStream(StreamUiState.Live(2L), summary),
        )
    }

    @Test
    fun a_changed_snapshot_updates_the_notification() {
        val policy = MonitoringPolicy()
        policy.onStream(StreamUiState.Connecting, summary = null)
        policy.onStream(StreamUiState.Live(1L), summary)

        assertEquals(
            MonitoringCommand.Update(TableSummary(available = 1, occupied = 4, unknown = 1)),
            policy.onStream(StreamUiState.Live(2L), TableSummary(available = 1, occupied = 4, unknown = 1)),
        )
    }

    @Test
    fun a_failed_stream_publishes_no_counts() {
        val policy = MonitoringPolicy()
        policy.onStream(StreamUiState.Connecting, summary = null)
        policy.onStream(StreamUiState.Live(1L), summary)

        assertEquals(
            "a dead camera must not keep advertising old totals",
            MonitoringCommand.Update(summary = null),
            policy.onStream(StreamUiState.Failed(StreamErrorKind.Closed), summary),
        )
    }

    @Test
    fun reconnecting_never_keeps_stale_counts() {
        val policy = MonitoringPolicy()
        policy.onStream(StreamUiState.Connecting, summary = null)
        policy.onStream(StreamUiState.Live(1L), summary)

        assertEquals(
            MonitoringCommand.Update(summary = null),
            policy.onStream(StreamUiState.Reconnecting(attempt = 1, nextRetryInMs = 1_000L), summary),
        )
    }

    @Test
    fun unknown_tables_stay_unknown_in_the_notification() {
        val policy = MonitoringPolicy()
        policy.onStream(StreamUiState.Connecting, summary = null)

        assertEquals(
            MonitoringCommand.Update(TableSummary(available = 0, occupied = 0, unknown = 5)),
            policy.onStream(StreamUiState.Live(1L), TableSummary(available = 0, occupied = 0, unknown = 5)),
        )
    }

    @Test
    fun closing_the_stream_stops_the_service_exactly_once() {
        val policy = MonitoringPolicy()
        policy.onStream(StreamUiState.Connecting, summary = null)
        policy.onStream(StreamUiState.Live(1L), summary)

        assertEquals(MonitoringCommand.Stop, policy.onStream(StreamUiState.Idle, summary = null))
        assertNull("a second idle tick must not stop again", policy.onStream(StreamUiState.Idle, null))
    }

    @Test
    fun a_reset_forgets_the_previous_session_so_the_next_open_starts_again() {
        val policy = MonitoringPolicy()
        policy.onStream(StreamUiState.Connecting, summary = null)
        policy.onStream(StreamUiState.Live(1L), summary)
        policy.reset()

        assertEquals(
            MonitoringCommand.Start(summary = null),
            policy.onStream(StreamUiState.Connecting, summary = null),
        )
    }
}