package id.warung.monitor.sync

import id.warung.monitor.core.model.SyncRequestEnvelope
import id.warung.monitor.core.sync.SyncEndpoint
import id.warung.monitor.core.sync.SyncResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport is the only place a status code becomes a decision. A wrong
 * mapping either drops events or retries a permanent rejection forever.
 */
class KtorSyncTransportTest {

    private val envelope = SyncRequestEnvelope(
        deviceId = "android-warnet-01",
        seq = 42,
        sentAtEpochMs = NOW,
        events = listOf("""{"table_id":"meja-01","status":"occupied","since":"2026-11-25T05:31:00Z"}"""),
    )

    private fun transport(
        endpoint: SyncEndpoint? = SyncEndpoint("https://vps.example.id", "token-abc"),
        captured: MutableList<HttpRequestData>? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorSyncTransport {
        val engine = MockEngine { request ->
            captured?.add(request)
            handler(request)
        }
        return KtorSyncTransport(HttpClient(engine)) { endpoint }
    }

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) = respond(
        content = body,
        status = status,
        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
    )

    @Test
    fun a_200_response_becomes_an_ack() = runTest {
        val transport = transport {
            json(
                HttpStatusCode.OK,
                """{"device_id":"android-warnet-01","acked_seq":42,"server_time":"2026-11-25T05:31:06Z","duplicates":0}""",
            )
        }

        val result = transport.send(envelope) as SyncResult.Acked

        assertEquals(42L, result.ack.ackedSeq)
        assertEquals(0, result.ack.duplicates)
    }

    @Test
    fun the_request_is_https_and_bearer_authenticated() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val transport = transport(
            handler = {
                json(
                    HttpStatusCode.OK,
                    """{"device_id":"d","acked_seq":42,"server_time":"2026-11-25T05:31:06Z","duplicates":0}""",
                )
            },
            captured = captured,
        )

        transport.send(envelope)

        val request = captured.single()
        assertEquals("https://vps.example.id/v1/sync", request.url.toString())
        assertEquals("Bearer token-abc", request.headers["Authorization"])
    }

    @Test
    fun a_conflict_becomes_a_sequence_gap_with_the_expected_seq() = runTest {
        val transport = transport {
            json(
                HttpStatusCode.Conflict,
                """{"error":{"code":"seq_gap","message":"expected seq 43"},"expected_seq":43}""",
            )
        }

        assertEquals(SyncResult.SequenceGap(43), transport.send(envelope))
    }

    @Test
    fun a_conflict_without_expected_seq_is_retryable_not_a_crash() = runTest {
        val transport = transport { json(HttpStatusCode.Conflict, """{"error":{"code":"seq_gap"}}""") }

        assertEquals(SyncResult.Retryable("seq_gap_without_expected_seq"), transport.send(envelope))
    }

    @Test
    fun a_413_asks_the_coordinator_to_split_the_batch() = runTest {
        val transport = transport { respondError(HttpStatusCode.PayloadTooLarge) }

        assertEquals(SyncResult.PayloadTooLarge, transport.send(envelope))
    }

    @Test
    fun authentication_failures_are_permanent() = runTest {
        assertEquals(
            SyncResult.Rejected("unauthorized"),
            transport { respondError(HttpStatusCode.Unauthorized) }.send(envelope),
        )
        assertEquals(
            SyncResult.Rejected("forbidden"),
            transport { respondError(HttpStatusCode.Forbidden) }.send(envelope),
        )
        assertEquals(
            SyncResult.Rejected("invalid_payload"),
            transport { respondError(HttpStatusCode.BadRequest) }.send(envelope),
        )
    }

    @Test
    fun server_errors_and_rate_limits_are_retried() = runTest {
        assertEquals(
            SyncResult.Retryable("server_error"),
            transport { respondError(HttpStatusCode.InternalServerError) }.send(envelope),
        )
        assertEquals(
            SyncResult.Retryable("rate_limited"),
            transport { respondError(HttpStatusCode.TooManyRequests) }.send(envelope),
        )
    }

    @Test
    fun a_network_failure_is_retryable_and_does_not_escape() = runTest {
        val transport = transport { throw java.io.IOException("connection reset") }

        assertEquals(SyncResult.Retryable("transport_error"), transport.send(envelope))
    }

    @Test
    fun without_a_configured_endpoint_nothing_is_sent() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val transport = transport(
            handler = { json(HttpStatusCode.OK, "{}") },
            endpoint = null,
            captured = captured,
        )

        assertEquals(SyncResult.Retryable("sync_not_configured"), transport.send(envelope))
        assertTrue(captured.isEmpty())
    }

    @Test
    fun a_plain_http_endpoint_is_rejected_before_storage() {
        val error = runCatching { SyncEndpoint("http://vps.example.id", "token") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        // The message must describe the rule, not the rejected value.
        assertTrue(error?.message?.contains("vps.example.id") == false)
    }

    @Test
    fun a_failure_reason_never_contains_the_token_or_host() {
        val reasons = listOf(
            SyncResult.Retryable("transport_error").reason,
            SyncResult.Rejected("unauthorized").reason,
        )

        reasons.forEach { reason ->
            assertTrue(!reason.contains("token-abc"))
            assertTrue(!reason.contains("vps.example.id"))
        }
    }

    private companion object {
        const val NOW = 1_795_584_660_000L
    }
}

