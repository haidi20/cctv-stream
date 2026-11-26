package id.warung.monitor.core.network

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal RTSP client used only for the connection test.
 *
 * Deliberately not a media pipeline: it sends `OPTIONS`, `DESCRIBE`, `SETUP`
 * (interleaved TCP), `PLAY`, then waits for the first interleaved RTP packet.
 * That is exactly enough to tell a wrong password from an unsupported codec from
 * a camera that never sends a frame.
 *
 * Security: the endpoint's secret is used to build the Basic/Digest header in
 * memory, is never stored on this object, and never appears in an exception or
 * message. Failures are reported as [ConnectionFailure] only.
 */
class SocketRtspSessionProbe(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
) : RtspSessionProbe {

    override suspend fun handshake(
        endpoint: RtspEndpoint,
        describeTimeoutMillis: Int,
        streamTimeoutMillis: Int,
    ): RtspProbeReport = withContext(Dispatchers.IO) {
        try {
            handshakeBlocking(endpoint, describeTimeoutMillis, streamTimeoutMillis)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            RtspProbeReport(
                describe = LayerOutcome.Failed(ConnectionFailure.Protocol),
                stream = LayerOutcome.NotRun,
            )
        }
    }

    private fun handshakeBlocking(
        endpoint: RtspEndpoint,
        describeTimeoutMillis: Int,
        streamTimeoutMillis: Int,
    ): RtspProbeReport {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMillis)
            socket.soTimeout = describeTimeoutMillis
            val output = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            val url = "rtsp://${endpoint.host}:${endpoint.port}${endpoint.path}"
            val reader = ResponseReader(input)
            var authorization: String? = null
            var cseq = 1

            // OPTIONS is the cheapest way to discover the auth scheme without a
            // second credential attempt: a 401 here is normal, not a failure.
            send(output, request("OPTIONS", url, cseq++, authorization))
            val options = try {
                reader.readResponse()
            } catch (error: java.net.SocketTimeoutException) {
                return failedDescribe(ConnectionFailure.Timeout)
            }
            if (options.status == 401) {
                authorization = options.authenticate(
                    method = "OPTIONS",
                    url = url,
                    username = endpoint.username,
                    secret = endpoint.secret,
                ) ?: return failedDescribe(ConnectionFailure.AuthFailed)
            }

            send(output, request("DESCRIBE", url, cseq++, authorization, acceptSdp = true))
            var describe = try {
                reader.readResponse()
            } catch (error: java.net.SocketTimeoutException) {
                return failedDescribe(ConnectionFailure.Timeout)
            }
            if (describe.status == 401) {
                authorization = describe.authenticate(
                    method = "DESCRIBE",
                    url = url,
                    username = endpoint.username,
                    secret = endpoint.secret,
                ) ?: return failedDescribe(ConnectionFailure.AuthFailed)
                send(output, request("DESCRIBE", url, cseq++, authorization, acceptSdp = true))
                describe = try {
                    reader.readResponse()
                } catch (error: java.net.SocketTimeoutException) {
                    return failedDescribe(ConnectionFailure.Timeout)
                }
            }
            when {
                describe.status == 401 || describe.status == 403 ->
                    return failedDescribe(ConnectionFailure.AuthFailed)
                describe.status == 404 || describe.status in 400..499 ->
                    return failedDescribe(ConnectionFailure.Protocol)
                describe.status >= 500 -> return failedDescribe(ConnectionFailure.Unreachable)
                describe.status !in 200..299 -> return failedDescribe(ConnectionFailure.Protocol)
            }

            val videoControl = describe.videoControl()
                ?: return failedDescribe(ConnectionFailure.Protocol)
            val setupUrl = resolveControl(url, videoControl)
            send(output, request("SETUP", setupUrl, cseq++, authorization, setupInterleaved = true))
            val setup = try {
                reader.readResponse()
            } catch (error: java.net.SocketTimeoutException) {
                return failedDescribe(ConnectionFailure.Timeout)
            }
            if (setup.status == 401) return failedDescribe(ConnectionFailure.AuthFailed)
            if (setup.status !in 200..299) return failedDescribe(ConnectionFailure.Protocol)

            val sessionId = setup.headers["session"]?.substringBefore(';')?.trim()
                ?: return failedDescribe(ConnectionFailure.Protocol)
            send(
                output,
                request("PLAY", url, cseq, authorization, sessionId = sessionId),
            )
            val play = try {
                reader.readResponse()
            } catch (error: java.net.SocketTimeoutException) {
                return failedDescribe(ConnectionFailure.Timeout)
            }
            if (play.status == 401) return failedDescribe(ConnectionFailure.AuthFailed)
            if (play.status !in 200..299) return failedDescribe(ConnectionFailure.Protocol)

            socket.soTimeout = streamTimeoutMillis
            val frame = try {
                reader.readInterleavedFrame()
            } catch (error: java.net.SocketTimeoutException) {
                InterleavedFrame.Timeout
            }
            val stream = when (frame) {
                InterleavedFrame.Media -> LayerOutcome.Passed
                InterleavedFrame.Timeout -> LayerOutcome.Failed(ConnectionFailure.Timeout)
                InterleavedFrame.Closed -> LayerOutcome.Failed(ConnectionFailure.Closed)
            }
            return RtspProbeReport(describe = LayerOutcome.Passed, stream = stream)
        }
    }

    private fun failedDescribe(failure: ConnectionFailure) = RtspProbeReport(
        describe = LayerOutcome.Failed(failure),
        stream = LayerOutcome.NotRun,
    )

    private fun request(
        method: String,
        url: String,
        cseq: Int,
        authorization: String?,
        acceptSdp: Boolean = false,
        setupInterleaved: Boolean = false,
        sessionId: String? = null,
    ): String = buildString {
        append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
        append("CSeq: ").append(cseq).append("\r\n")
        append("User-Agent: warung-monitor-connection-test\r\n")
        if (acceptSdp) append("Accept: application/sdp\r\n")
        if (setupInterleaved) {
            // Track 0 with RTP/RTCP interleaved on channels 0/1.
            append("Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
        }
        if (sessionId != null) append("Session: ").append(sessionId).append("\r\n")
        if (authorization != null) append("Authorization: ").append(authorization).append("\r\n")
        append("\r\n")
    }

    private fun send(output: BufferedOutputStream, text: String) {
        output.write(text.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** Splits a DESCRIBE SDP body into the first video media control URL. */
    private fun RtspResponse.videoControl(): String? {
        val body = body ?: return null
        var inVideo = false
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("m=") -> inVideo = line.startsWith("m=video")
                line.startsWith("a=control:") && inVideo -> return line.removePrefix("a=control:").trim()
            }
        }
        return null
    }

    private fun resolveControl(baseUrl: String, control: String): String = when {
        control.startsWith("rtsp://") -> control
        control.startsWith("*") -> baseUrl
        else -> baseUrl.trimEnd('/') + "/" + control.trimStart('/')
    }

    private sealed interface InterleavedFrame {
        data object Media : InterleavedFrame
        data object Timeout : InterleavedFrame
        data object Closed : InterleavedFrame
    }

    /** Long enough to hold RTSP headers plus a small SDP body. */
    private class ResponseReader(private val input: BufferedInputStream) {

        /** Reads one RTSP response; `$`-frames are skipped. */
        fun readResponse(): RtspResponse {
            val statusLine = readLine() ?: throw ClosedChannel()
            var line = readLine()
            val headers = LinkedHashMap<String, String>()
            while (line != null && line.isNotEmpty()) {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
                line = readLine()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) {
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(bytes, read, length - read)
                    if (count < 0) throw ClosedChannel()
                    read += count
                }
                String(bytes, Charsets.UTF_8)
            } else {
                null
            }
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            return RtspResponse(status = status, headers = headers, body = body)
        }

        /** Waits until the camera sends a media packet on the interleaved channel. */
        fun readInterleavedFrame(): InterleavedFrame {
            while (true) {
                val first = input.read()
                if (first < 0) return InterleavedFrame.Closed
                if (first != DOLLAR) {
                    // A late RTSP response; skip to the next `$` without buffering.
                    continue
                }
                val channel = input.read()
                if (channel < 0) return InterleavedFrame.Closed
                val length = readTwoBytes() ?: return InterleavedFrame.Closed
                var remaining = length
                while (remaining > 0) {
                    val skipped = input.skip(remaining.toLong())
                    if (skipped <= 0) {
                        if (input.read() < 0) return InterleavedFrame.Closed
                        remaining -= 1
                    } else {
                        remaining -= skipped.toInt()
                    }
                }
                return InterleavedFrame.Media
            }
        }

        private fun readTwoBytes(): Int? {
            val high = input.read()
            if (high < 0) return null
            val low = input.read()
            if (low < 0) return null
            return (high shl 8) or low
        }

        private fun readLine(): String? {
            val builder = StringBuilder(64)
            while (true) {
                val value = input.read()
                when {
                    value < 0 -> return if (builder.isEmpty()) null else builder.toString()
                    value == '\n'.code -> return builder.toString().trimEnd('\r')
                    else -> builder.append(value.toChar())
                }
            }
        }
    }

    private class ClosedChannel : Exception()

    private data class RtspResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: String?,
    ) {
        /**
         * Builds the Basic or Digest header for the next request.
         *
         * Returns `null` when the camera asks for a scheme this probe cannot
         * implement, or when the user typed no username/secret.
         */
        fun authenticate(
            method: String,
            url: String,
            username: String?,
            secret: CharArray?,
        ): String? {
            if (username.isNullOrBlank() || secret == null || secret.isEmpty()) return null
            val challenge = headers["www-authenticate"] ?: return null
            return when {
                challenge.startsWith("Basic", ignoreCase = true) ->
                    "Basic " + base64("$username:${String(secret)}")
                challenge.startsWith("Digest", ignoreCase = true) ->
                    digestHeader(challenge, method, url, username, secret)
                else -> null
            }
        }

        private fun digestHeader(
            challenge: String,
            method: String,
            url: String,
            username: String,
            secret: CharArray,
        ): String {
            val params = challenge.removePrefix("Digest").trim()
                .split(',')
                .mapNotNull { part ->
                    val index = part.indexOf('=')
                    if (index <= 0) return@mapNotNull null
                    val name = part.substring(0, index).trim().lowercase()
                    val value = part.substring(index + 1).trim().trim('"')
                    name to value
                }
                .toMap()
            val realm = params["realm"].orEmpty()
            val nonce = params["nonce"].orEmpty()
            val qop = params["qop"]?.substringBefore(',').orEmpty()
            val opaque = params["opaque"]
            val algorithm = params["algorithm"]
            val ha1 = md5Hex("$username:$realm:${String(secret)}")
            val ha2 = md5Hex("$method:$url")
            val nc = "00000001"
            val cnonce = Random.nextLong().toULong().toString(16).padStart(16, '0')
            val response = if (qop.equals("auth", ignoreCase = true)) {
                md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
            } else {
                md5Hex("$ha1:$nonce:$ha2")
            }
            return buildString {
                append("Digest username=\"").append(username).append("\"")
                append(", realm=\"").append(realm).append("\"")
                append(", nonce=\"").append(nonce).append("\"")
                append(", uri=\"").append(url).append("\"")
                append(", response=\"").append(response).append("\"")
                if (qop.isNotEmpty()) append(", qop=").append(qop).append(", nc=").append(nc)
                    .append(", cnonce=\"").append(cnonce).append("\"")
                if (opaque != null) append(", opaque=\"").append(opaque).append("\"")
                if (algorithm != null) append(", algorithm=").append(algorithm)
            }
        }

        private fun md5Hex(value: String): String =
            MessageDigest.getInstance("MD5")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }

        private fun base64(value: String): String =
            java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 1_500
        private const val DOLLAR = 0x24
    }
}