package id.warung.monitor.sync

import id.warung.monitor.core.model.HeartbeatDeviceProfile
import id.warung.monitor.core.model.HeartbeatPayload
import id.warung.monitor.core.model.HeartbeatStreamStatus
import id.warung.monitor.core.model.TableSummary
import id.warung.monitor.core.sync.HeartbeatResult
import id.warung.monitor.core.sync.HeartbeatTransport
import id.warung.monitor.core.sync.SyncEndpoint
import id.warung.monitor.core.sync.SyncSettings
import id.warung.monitor.feature.home.domain.EnginePhase
import id.warung.monitor.feature.home.domain.EngineStatus
import id.warung.monitor.feature.home.domain.EngineStatusSource
import id.warung.monitor.feature.home.domain.StreamErrorKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/tdd-plan.md` expects `HeartbeatBuilderTest`; this adds the runner that
 * decides cadence and never queues a disposable heartbeat.
 */
class HeartbeatRunnerTest {

    private class FakeSettings(private val endpoint: SyncEndpoint?) : SyncSettings {
        override suspend fun endpoint(): SyncEndpoint? = endpoint
        override suspend fun configuredBaseUrl(): String? = endpoint?.baseUrl
        override suspend fun hasToken(): Boolean = endpoint?.token != null
        override suspend fun saveBaseUrl(baseUrl: String) = Unit
        override suspend fun saveToken(token: String?) = Unit
        override suspend fun clear() = Unit
    }

    private class FakeTransport(private val result: HeartbeatResult) : HeartbeatTransport {
        val sent = mutableListOf<HeartbeatPayload>()

        override suspend fun send(payload: HeartbeatPayload): HeartbeatResult {
            sent += payload
            return result
        }
    }

    private fun status(
        phase: EnginePhase,
        failure: StreamErrorKind? = null,
    ) = EngineStatus(
        available = true,
        cameraId = "kamera-01",
        phase = phase,
        failure = failure,
        framesIn = 1_000L,
        tables = 12,
        availableCount = 5,
        occupiedCount = 6,
        unknownCount = 1,
        modelReady = true,
        demo = false,
    )

    private val facts = HeartbeatDeviceFacts(
        deviceId = "android-warnet-01",
        sessionId = "3f0c9a1e",
        appVersion = "0.1.0",
        profile = HeartbeatDeviceProfile.BALANCED,
    )

    private fun runner(
        engine: EngineStatus,
        transport: HeartbeatTransport,
        endpoint: SyncEndpoint? = SyncEndpoint("https://vps.example.com", "token"),
    ) = HeartbeatRunner(
        statusSource = EngineStatusSource { engine },
        facts = { facts },
        transport = transport,
        settings = FakeSettings(endpoint),
        clock = { NOW },
    )

    @Test
    fun a_live_device_schedules_the_next_heartbeat_in_thirty_seconds() = runTest {
        val transport = FakeTransport(HeartbeatResult.Accepted)
        val runner = runner(status(EnginePhase.Live), transport)

        assertEquals(HeartbeatRunner.Decision.SentLive, runner.run())
        assertEquals(1, transport.sent.size)
        assertEquals(HeartbeatStreamStatus.CONNECTED, transport.sent.single().stream.status)
    }

    @Test
    fun an_idle_device_schedules_the_next_heartbeat_in_sixty_seconds() = runTest {
        val transport = FakeTransport(HeartbeatResult.Accepted)
        val runner = runner(status(EnginePhase.Idle), transport)

        assertEquals(HeartbeatRunner.Decision.SentIdle, runner.run())
    }

    @Test
    fun without_a_base_url_no_request_is_made_and_the_chain_stops() = runTest {
        val transport = FakeTransport(HeartbeatResult.Accepted)
        val runner = runner(status(EnginePhase.Live), transport, endpoint = null)

        assertEquals(HeartbeatRunner.Decision.NotConfigured, runner.run())
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun a_retryable_failure_waits_the_idle_interval_instead_of_dropping_forever() = runTest {
        val runner = runner(status(EnginePhase.Live), FakeTransport(HeartbeatResult.Retryable("offline")))

        assertEquals(
            HeartbeatRunner.Decision.Retry(HeartbeatScheduler.IDLE_INTERVAL_MILLIS),
            runner.run(),
        )
    }

    @Test
    fun a_rejected_token_stops_the_chain_until_the_user_saves_new_credentials() = runTest {
        val runner = runner(status(EnginePhase.Live), FakeTransport(HeartbeatResult.Rejected("unauthorized")))

        assertEquals(HeartbeatRunner.Decision.Stop("unauthorized"), runner.run())
    }

    @Test
    fun a_dead_stream_is_reported_as_its_real_cause() = runTest {
        val transport = FakeTransport(HeartbeatResult.Accepted)
        val runner = runner(status(EnginePhase.Failed, StreamErrorKind.AuthFailed), transport)

        runner.run()
        assertEquals(HeartbeatStreamStatus.AUTH_FAILED, transport.sent.single().stream.status)
        assertEquals(TableSummary(5, 6, 1), transport.sent.single().tables)
    }

    private companion object {
        const val NOW = 1_795_584_665_000L
    }
}
