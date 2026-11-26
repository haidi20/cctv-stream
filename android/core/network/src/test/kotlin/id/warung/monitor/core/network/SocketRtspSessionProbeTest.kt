package id.warung.monitor.core.network

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.util.Base64
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [SocketRtspSessionProbe] against a real loopback RTSP server.
 *
 * These tests exercise the socket parsing and auth code that a fake cannot:
 * header folding, `Content-Length`, interleaved `$` frames, and the difference
 * between a 401 and a 200.
 */
class SocketRtspSessionProbeTest {

    private val endpoint = RtspEndpoint(
        host = "127.0.0.1",
        port = 0,
        path = "/stream",
        username = "admin",
        secret = "hunter2".toCharArray(),
    )

    @Test
    fun basic_auth_succeeds_and_the_first_interleaved_frame_passes_both_layers() = runTest {
        withServer(authenticatedVideoServer()) { port ->
            val report = SocketRtspSessionProbe().handshake(
                endpoint.copy(port = port),
                describeTimeoutMillis = 2_000,
                streamTimeoutMillis = 2_000,
            )

            assertEquals(LayerOutcome.Passed, report.describe)
            assertEquals(LayerOutcome.Passed, report.stream)
        }
    }

    @Test
    fun a_wrong_password_returns_auth_failed_and_no_stream_layer() = runTest {
        withServer(authenticatedVideoServer()) { port ->
            val report = SocketRtspSessionProbe().handshake(
                endpoint.copy(port = port, secret = "salah".toCharArray()),
                describeTimeoutMillis = 2_000,
                streamTimeoutMillis = 2_000,
            )

            assertEquals(LayerOutcome.Failed(ConnectionFailure.AuthFailed), report.describe)
            assertEquals(LayerOutcome.NotRun, report.stream)
        }
    }

    @Test
    fun digest_auth_uses_the_challenge_nonce_and_never_sends_the_plain_password() = runTest {
        val seen = mutableListOf<String>()
        withServer(digestServer(seen)) { port ->
            val report = SocketRtspSessionProbe().handshake(
                endpoint.copy(port = port),
                describeTimeoutMillis = 2_000,
                streamTimeoutMillis = 2_000,
            )

            assertEquals(LayerOutcome.Passed, report.describe)
            assertEquals(LayerOutcome.Passed, report.stream)
        }
        val authorization = seen.firstOrNull { it.startsWith("Authorization:") }.orEmpty()
        assertTrue("digest header expected, got: $authorization", authorization.contains("Digest"))
        assertTrue(authorization.contains("nonce=\"abc123\""))
        assertTrue("plain secret must not travel", !authorization.contains("hunter2"))
    }

    @Test
    fun a_camera_that_never_sends_a_frame_reports_a_stream_timeout() = runTest {
        withServer(noFrameServer()) { port ->
            val report = SocketRtspSessionProbe().handshake(
                endpoint.copy(port = port),
                describeTimeoutMillis = 2_000,
                streamTimeoutMillis = 300,
            )

            assertEquals(LayerOutcome.Passed, report.describe)
            assertEquals(LayerOutcome.Failed(ConnectionFailure.Timeout), report.stream)
        }
    }

    @Test
    fun a_camera_with_no_video_track_is_a_protocol_failure() = runTest {
        withServer(audioOnlyServer()) { port ->
            val report = SocketRtspSessionProbe().handshake(
                endpoint.copy(port = port),
                describeTimeoutMillis = 2_000,
                streamTimeoutMillis = 2_000,
            )

            assertEquals(LayerOutcome.Failed(ConnectionFailure.Protocol), report.describe)
        }
    }

    @Test
    fun the_url_and_password_never_appear_in_the_report() = runTest {
        withServer(authenticatedVideoServer()) { port ->
            val report = SocketRtspSessionProbe().handshake(
                endpoint.copy(port = port),
                describeTimeoutMillis = 2_000,
                streamTimeoutMillis = 2_000,
            )

            assertTrue(!report.toString().contains("hunter2"))
            assertTrue(!report.toString().contains("/stream"))
        }
    }

    // --- loopback servers ---------------------------------------------------

    private fun authenticatedVideoServer(): (ServerSocket) -> Unit = { server ->
        val client = server.accept()
        client.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
            val output = it.getOutputStream()
            while (true) {
                val requestLine = reader.readLine() ?: break
                val headers = readHeaders(reader)
                val method = requestLine.substringBefore(' ')
                val authorized = headers["authorization"]
                    ?.equals("Basic " + Base64.getEncoder().encodeToString("admin:hunter2".toByteArray()))
                    ?: false
                if (!authorized) {
                    respond(output, 401, "WWW-Authenticate: Basic realm=\"cam\"")
                    continue
                }
                when (method) {
                    "OPTIONS" -> respond(output, 200, "Public: OPTIONS, DESCRIBE, SETUP, PLAY")
                    "DESCRIBE" -> respond(
                        output,
                        200,
                        "Content-Type: application/sdp",
                        body = SDP,
                    )
                    "SETUP" -> respond(output, 200, "Session: 12345678;timeout=60")
                    "PLAY" -> {
                        respond(output, 200, "Session: 12345678")
                        output.write(byteArrayOf(0x24, 0x00, 0x00, 0x04, 0x80.toByte(), 0x60, 0x00, 0x01))
                        output.flush()
                        return@use
                    }
                }
            }
        }
    }

    private fun digestServer(seen: MutableList<String>): (ServerSocket) -> Unit = { server ->
        val client = server.accept()
        client.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
            val output = it.getOutputStream()
            var challenged = false
            while (true) {
                val requestLine = reader.readLine() ?: break
                val headers = readHeaders(reader)
                headers.forEach { (name, value) -> if (name == "authorization") seen += "Authorization: $value" }
                val method = requestLine.substringBefore(' ')
                if (!challenged) {
                    challenged = true
                    respond(
                        output,
                        401,
                        "WWW-Authenticate: Digest realm=\"cam\", nonce=\"abc123\", qop=\"auth\"",
                    )
                    continue
                }
                when (method) {
                    "OPTIONS", "DESCRIBE" -> if (method == "DESCRIBE") {
                        respond(output, 200, "Content-Type: application/sdp", body = SDP)
                    } else {
                        respond(output, 200, "Public: OPTIONS")
                    }
                    "SETUP" -> respond(output, 200, "Session: 12345678")
                    "PLAY" -> {
                        respond(output, 200, "Session: 12345678")
                        output.write(byteArrayOf(0x24, 0x00, 0x00, 0x02, 0x80.toByte(), 0x21))
                        output.flush()
                        return@use
                    }
                }
            }
        }
    }

    private fun noFrameServer(): (ServerSocket) -> Unit = { server ->
        val client = server.accept()
        client.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
            val output = it.getOutputStream()
            while (true) {
                val requestLine = reader.readLine() ?: break
                readHeaders(reader)
                when (requestLine.substringBefore(' ')) {
                    "OPTIONS" -> respond(output, 200, "Public: OPTIONS")
                    "DESCRIBE" -> respond(output, 200, "Content-Type: application/sdp", body = SDP)
                    "SETUP" -> respond(output, 200, "Session: 12345678")
                    "PLAY" -> {
                        respond(output, 200, "Session: 12345678")
                        Thread.sleep(1_500)
                        return@use
                    }
                }
            }
        }
    }

    private fun audioOnlyServer(): (ServerSocket) -> Unit = { server ->
        val client = server.accept()
        client.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
            val output = it.getOutputStream()
            while (true) {
                val requestLine = reader.readLine() ?: break
                readHeaders(reader)
                val method = requestLine.substringBefore(' ')
                if (method == "OPTIONS") respond(output, 200, "Public: OPTIONS")
                if (method == "DESCRIBE") {
                    respond(
                        output,
                        200,
                        "Content-Type: application/sdp",
                        body = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=No video\r\nm=audio 0 RTP/AVP 0\r\na=control:trackID=1\r\n",
                    )
                }
            }
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }
        return headers
    }

    private fun respond(
        output: OutputStream,
        status: Int,
        vararg extraHeaders: String,
        body: String? = null,
    ) {
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val text = buildString {
            append("RTSP/1.0 ").append(status).append(' ')
            append(if (status == 200) "OK" else "Unauthorized").append("\r\n")
            append("CSeq: 1\r\n")
            extraHeaders.forEach { append(it).append("\r\n") }
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("\r\n")
        }
        val out = ByteArrayOutputStream()
        out.write(text.toByteArray())
        out.write(bytes)
        output.write(out.toByteArray())
        output.flush()
    }

    private suspend fun withServer(
        handler: (ServerSocket) -> Unit,
        block: suspend (Int) -> Unit,
    ) {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                runCatching { handler(server) }
            }
            block(server.localPort)
            worker.join(2_000)
        }
    }

    private companion object {
        const val SDP = "v=0\r\n" +
            "o=- 0 0 IN IP4 127.0.0.1\r\n" +
            "s=Camera\r\n" +
            "t=0 0\r\n" +
            "m=video 0 RTP/AVP 96\r\n" +
            "a=rtpmap:96 H264/90000\r\n" +
            "a=control:trackID=0\r\n"
    }
}