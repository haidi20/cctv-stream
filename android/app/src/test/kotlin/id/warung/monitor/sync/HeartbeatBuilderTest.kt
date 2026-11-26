package id.warung.monitor.sync

import id.warung.monitor.core.model.HeartbeatDeviceProfile
import id.warung.monitor.core.model.HeartbeatJson
import id.warung.monitor.core.model.HeartbeatQueue
import id.warung.monitor.core.model.HeartbeatStreamStatus
import id.warung.monitor.core.model.HeartbeatTransport
import id.warung.monitor.core.model.TableSummary
import id.warung.monitor.feature.home.domain.EnginePhase
import id.warung.monitor.feature.home.domain.EngineStatus
import id.warung.monitor.feature.home.domain.StreamErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/tdd-plan.md` lists `HeartbeatBuilderTest` as the guard for the
 * heartbeat payload. The important failure it prevents is a dead camera
 * looking healthy, or a missing measurement being sent as zero.
 */
class HeartbeatBuilderTest {

    private val facts = HeartbeatDeviceFacts(
        deviceId = "android-warnet-01",
        sessionId = "3f0c9a1e",
        appVersion = "0.1.0",
        configVersion = 4L,
        profile = HeartbeatDeviceProfile.BALANCED,
        transport = HeartbeatTransport.WIFI,
        queue = HeartbeatQueue(pending = 7L),
    )

    private fun status(
        phase: EnginePhase,
        failure: StreamErrorKind? = null,
        tables: Int = 12,
        available: Boolean = true,
        modelReady: Boolean = true,
    ) = EngineStatus(
        available = available,
        cameraId = "kamera-01",
        phase = phase,
        failure = failure,
        framesIn = 1_000L,
        tables = tables,
        availableCount = 5,
        occupiedCount = 6,
        unknownCount = 1,
        modelReady = modelReady,
        demo = false,
    )

    @Test
    fun a_live_stream_reports_connected_and_carries_the_table_counts() {
        val payload = HeartbeatBuilder.build(status(EnginePhase.Live), facts, NOW)

        assertEquals(HeartbeatStreamStatus.CONNECTED, payload.stream.status)
        assertNull("a live stream has no last_error", payload.stream.lastError)
        assertEquals(TableSummary(5, 6, 1), payload.tables)
        assertEquals(7L, payload.queue?.pending)
    }

    @Test
    fun an_auth_failure_is_reported_as_auth_failed_not_as_a_network_problem() {
        val payload = HeartbeatBuilder.build(
            status(EnginePhase.Failed, failure = StreamErrorKind.AuthFailed),
            facts,
            NOW,
        )

        assertEquals(HeartbeatStreamStatus.AUTH_FAILED, payload.stream.status)
        assertEquals(HeartbeatStreamStatus.AUTH_FAILED, payload.stream.lastError)
    }

    @Test
    fun kotlin_only_causes_keep_their_own_wire_value() {
        assertEquals(
            HeartbeatStreamStatus.NO_NETWORK,
            HeartbeatBuilder.streamBlock(status(EnginePhase.Failed, StreamErrorKind.NoNetwork)).status,
        )
        assertEquals(
            HeartbeatStreamStatus.WRONG_NETWORK,
            HeartbeatBuilder.streamBlock(status(EnginePhase.Failed, StreamErrorKind.WrongNetwork)).status,
        )
    }

    @Test
    fun a_missing_library_is_error_not_idle() {
        val unavailable = EngineStatus.Unavailable

        assertEquals(HeartbeatStreamStatus.ERROR, HeartbeatBuilder.streamBlock(unavailable).status)
        assertNull("an unavailable engine has no counts", HeartbeatBuilder.build(unavailable, facts, NOW).tables)
    }

    @Test
    fun idle_is_never_written_as_a_last_error() {
        val block = HeartbeatBuilder.streamBlock(status(EnginePhase.Idle))

        assertEquals(HeartbeatStreamStatus.IDLE, block.status)
        assertNull(block.lastError)
    }

    @Test
    fun an_unknown_count_survives_the_encoder_as_its_own_bucket() {
        val text = HeartbeatJson.encode(HeartbeatBuilder.build(status(EnginePhase.Live), facts, NOW))

        assertTrue(text.contains("\"unknown\":1"))
        assertTrue(text.contains("\"available\":5"))
        assertTrue(text.contains("\"occupied\":6"))
    }

    @Test
    fun the_payload_never_contains_a_rtsp_url_or_a_credential() {
        val text = HeartbeatJson.encode(
            HeartbeatBuilder.build(status(EnginePhase.Live), facts, NOW),
        ).lowercase()

        listOf("rtsp://", "password", "token", "ssid", "\"ip\"", "frame").forEach { banned ->
            assertFalse("heartbeat leaked $banned", text.contains(banned))
        }
    }

    @Test
    fun a_dead_stream_still_reports_the_last_known_counts_but_marks_the_status() {
        // The contract says unknown tables must not be folded into available;
        // the status carries the outage and the counts stay explicit.
        val payload = HeartbeatBuilder.build(
            status(EnginePhase.Failed, failure = StreamErrorKind.Timeout),
            facts,
            NOW,
        )

        assertEquals(HeartbeatStreamStatus.TIMEOUT, payload.stream.status)
        assertEquals(1, payload.tables?.unknown)
    }

    private companion object {
        const val NOW = 1_795_584_665_000L
    }
}
