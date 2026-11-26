package id.warung.monitor.sync

import id.warung.monitor.core.model.HeartbeatJson
import id.warung.monitor.core.model.HeartbeatPayload
import id.warung.monitor.core.sync.HeartbeatResult
import id.warung.monitor.core.sync.HeartbeatTransport
import id.warung.monitor.core.sync.SyncEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

/**
 * `POST /v1/heartbeat` over Ktor.
 *
 * A heartbeat is metadata only; it never carries a frame, URL, or token in the
 * body. The response body is ignored on success, and failure text is a fixed
 * reason string so a misbehaving server cannot inject text into logs or UI.
 */
class KtorHeartbeatTransport(
    private val client: HttpClient,
    private val endpoint: suspend () -> SyncEndpoint?,
) : HeartbeatTransport {

    override suspend fun send(payload: HeartbeatPayload): HeartbeatResult {
        val target = endpoint() ?: return HeartbeatResult.Retryable("sync_not_configured")
        val body = HeartbeatJson.encode(payload)
        return try {
            val response = client.post(target.heartbeatUrl()) {
                contentType(ContentType.Application.Json)
                target.token?.let { header("Authorization", "Bearer $it") }
                setBody(body)
            }
            when (response.status.value) {
                HttpStatusCode.OK.value, HttpStatusCode.Created.value, HttpStatusCode.NoContent.value ->
                    HeartbeatResult.Accepted

                HttpStatusCode.BadRequest.value -> HeartbeatResult.Rejected("invalid_payload")
                HttpStatusCode.Unauthorized.value -> HeartbeatResult.Rejected("unauthorized")
                HttpStatusCode.Forbidden.value -> HeartbeatResult.Rejected("forbidden")
                HttpStatusCode.TooManyRequests.value -> HeartbeatResult.Retryable("rate_limited")
                else -> {
                    if (response.status.value >= 500) {
                        HeartbeatResult.Retryable("server_error")
                    } else {
                        HeartbeatResult.Rejected("http_${response.status.value}")
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            HeartbeatResult.Retryable("transport_error")
        }
    }
}
