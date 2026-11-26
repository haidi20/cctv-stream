package id.warung.monitor.sync

import id.warung.monitor.core.model.SyncAckJson
import id.warung.monitor.core.model.SyncRequestEnvelope
import id.warung.monitor.core.model.SyncRequestJson
import id.warung.monitor.core.sync.SyncEndpoint
import id.warung.monitor.core.sync.SyncResult
import id.warung.monitor.core.sync.SyncTransport
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `POST /v1/sync` over Ktor.
 *
 * Every HTTP outcome is mapped to a [SyncResult] so the coordinator never sees
 * an exception; a bug in this class must degrade to `Retryable`, not to a lost
 * event. Error bodies are parsed only for `expected_seq`, and never echoed into
 * logs: they may contain request ids, not credentials, but the same rule keeps
 * a misbehaving server from writing arbitrary text into the outbox.
 */
class KtorSyncTransport(
    private val client: HttpClient,
    private val endpoint: suspend () -> SyncEndpoint?,
) : SyncTransport {

    override suspend fun send(envelope: SyncRequestEnvelope): SyncResult {
        val target = endpoint() ?: return SyncResult.Retryable("sync_not_configured")
        val body = SyncRequestJson.encode(envelope)
        return try {
            val response = client.post(target.syncUrl()) {
                contentType(ContentType.Application.Json)
                target.token?.let { header("Authorization", "Bearer $it") }
                setBody(body)
            }
            when (response.status.value) {
                HttpStatusCode.OK.value, HttpStatusCode.Created.value -> {
                    SyncResult.Acked(SyncAckJson.decode(response.bodyAsText()))
                }

                HttpStatusCode.Conflict.value -> {
                    val expected = expectedSeq(response.bodyAsText())
                    if (expected == null) {
                        SyncResult.Retryable("seq_gap_without_expected_seq")
                    } else {
                        SyncResult.SequenceGap(expected)
                    }
                }

                HttpStatusCode.PayloadTooLarge.value -> SyncResult.PayloadTooLarge
                HttpStatusCode.BadRequest.value -> SyncResult.Rejected("invalid_payload")
                HttpStatusCode.Unauthorized.value -> SyncResult.Rejected("unauthorized")
                HttpStatusCode.Forbidden.value -> SyncResult.Rejected("forbidden")
                HttpStatusCode.TooManyRequests.value -> SyncResult.Retryable("rate_limited")
                else -> {
                    if (response.status.value >= 500) {
                        SyncResult.Retryable("server_error")
                    } else {
                        SyncResult.Rejected("http_${response.status.value}")
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            SyncResult.Retryable("transport_error")
        }
    }

    private fun expectedSeq(body: String): Long? = try {
        Json.parseToJsonElement(body).jsonObject["expected_seq"]?.jsonPrimitive?.content?.toLongOrNull()
    } catch (_: Throwable) {
        null
    }
}

