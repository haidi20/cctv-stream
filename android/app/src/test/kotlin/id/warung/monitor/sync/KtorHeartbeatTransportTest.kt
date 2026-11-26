package id.warung.monitor.sync

import id.warung.monitor.core.model.HeartbeatDevice
import id.warung.monitor.core.model.HeartbeatDeviceProfile
import id.warung.monitor.core.model.HeartbeatPayload
import id.warung.monitor.core.model.HeartbeatRuntime
import id.warung.monitor.core.model.HeartbeatStream
import id.warung.monitor.core.model.HeartbeatStreamStatus
import id.warung.monitor.core.sync.HeartbeatResult
import id.warung.monitor.core.sync.SyncEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorHeartbeatTransportTest {

    private val payload = HeartbeatPayload(
        deviceId = "android-warnet-01",
        sessionId = "3f0c9a1e",
        sentAtEpochMs = 1_795_584_665_000L,
        stream = HeartbeatStream(HeartbeatStreamStatus.CONNECTED),
        device = HeartbeatDevice(HeartbeatDeviceProfile.BALANCED),
        runtime = HeartbeatRuntime(modelReady = true),
    )

    private fun transport(
        status: HttpStatusCode,
        endpoint: SyncEndpoint? = SyncEndpoint("https://vps.example.com", "secret-token"),
        capture: (HttpRequestData) -> Unit = {},
    ): KtorHeartbeatTransport {
        val client = HttpClient(
            MockEngine { request ->
                capture(request)
                respond("", status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        return KtorHeartbeatTransport(client) { endpoint }
    }

    @Test
    fun a_two_hundred_is_accepted_and_hits_the_heartbeat_path_with_a_bearer_token() = runTest {
        var url: String? = null
        var authorization: String? = null
        val transport = transport(HttpStatusCode.OK) { request ->
            url = request.url.toString()
            authorization = request.headers[HttpHeaders.Authorization]
        }

        assertEquals(HeartbeatResult.Accepted, transport.send(payload))
        assertEquals("https://vps.example.com/v1/heartbeat", url)
        assertEquals("Bearer secret-token", authorization)
    }

    @Test
    fun without_an_endpoint_nothing_is_sent() = runTest {
        var called = false
        val transport = transport(HttpStatusCode.OK, endpoint = null) { called = true }

        assertEquals(HeartbeatResult.Retryable("sync_not_configured"), transport.send(payload))
        assertFalse(called)
    }

    @Test
    fun unauthorized_is_permanent_and_does_not_echo_a_server_body() = runTest {
        val transport = transport(HttpStatusCode.Unauthorized)

        assertEquals(HeartbeatResult.Rejected("unauthorized"), transport.send(payload))
    }

    @Test
    fun rate_limiting_and_server_errors_are_retryable() = runTest {
        assertEquals(
            HeartbeatResult.Retryable("rate_limited"),
            transport(HttpStatusCode.TooManyRequests).send(payload),
        )
        assertEquals(
            HeartbeatResult.Retryable("server_error"),
            transport(HttpStatusCode.InternalServerError).send(payload),
        )
    }

    @Test
    fun a_transport_exception_degrades_to_retryable_instead_of_throwing() = runTest {
        val client = HttpClient(MockEngine { throw java.io.IOException("boom") })
        val transport = KtorHeartbeatTransport(client) {
            SyncEndpoint("https://vps.example.com", "secret-token")
        }

        assertEquals(HeartbeatResult.Retryable("transport_error"), transport.send(payload))
    }

    @Test
    fun the_request_body_is_metadata_only() = runTest {
        var body: String? = null
        val transport = transport(HttpStatusCode.OK) { request ->
            body = (request.body as? io.ktor.http.content.TextContent)?.text
        }

        transport.send(payload)
        val text = body.orEmpty().lowercase()
        assertTrue(text.contains("\"device_id\""))
        listOf("rtsp://", "password", "\"token\"", "ssid").forEach { banned ->
            assertFalse("heartbeat body leaked $banned", text.contains(banned))
        }
    }
}
