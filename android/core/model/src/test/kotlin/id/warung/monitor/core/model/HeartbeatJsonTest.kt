package id.warung.monitor.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/data-contracts.md` section 5 fixes the heartbeat document. These tests
 * pin the parts the dashboard depends on: status names, `unknown` surviving as
 * itself, and optional blocks being absent rather than zero.
 */
class HeartbeatJsonTest {

    private fun encode(payload: HeartbeatPayload): String = HeartbeatJson.encode(payload)

    private fun minimal(
        status: HeartbeatStreamStatus = HeartbeatStreamStatus.CONNECTED,
        tables: TableSummary? = null,
    ) = HeartbeatPayload(
        deviceId = "android-warung-01",
        sessionId = "3f0c9a1e",
        sentAtEpochMs = 1_795_584_665_000L,
        stream = HeartbeatStream(status = status),
        device = HeartbeatDevice(profile = HeartbeatDeviceProfile.BALANCED),
        runtime = HeartbeatRuntime(modelReady = true),
        tables = tables,
    )

    @Test
    fun required_blocks_are_always_present() {
        val root = Json.parseToJsonElement(encode(minimal())).jsonObject

        assertEquals("android-warung-01", root.getValue("device_id").jsonPrimitive.content)
        assertEquals("3f0c9a1e", root.getValue("session_id").jsonPrimitive.content)
        assertEquals("2026-11-25T05:31:05Z", root.getValue("sent_at").jsonPrimitive.content)
        assertEquals("connected", root.getValue("stream").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("balanced", root.getValue("device").jsonObject.getValue("profile").jsonPrimitive.content)
        assertEquals("true", root.getValue("runtime").jsonObject.getValue("model_ready").jsonPrimitive.content)
    }

    @Test
    fun unmeasured_blocks_are_omitted_instead_of_written_as_zero() {
        val root = Json.parseToJsonElement(encode(minimal())).jsonObject

        assertFalse("network block must not appear", root.containsKey("network"))
        assertFalse("inference block must not appear", root.containsKey("inference"))
        assertFalse("queue block must not appear", root.containsKey("queue"))
        assertFalse("app_version is optional", root.containsKey("app_version"))
        assertFalse("config_version is optional", root.containsKey("config_version"))
        assertFalse("tables is optional", root.containsKey("tables"))
    }

    @Test
    fun unknown_table_counts_are_never_folded_into_available() {
        val root = Json.parseToJsonElement(
            encode(minimal(tables = TableSummary(available = 6, occupied = 5, unknown = 1))),
        ).jsonObject

        val tables = root.getValue("tables").jsonObject
        assertEquals("6", tables.getValue("available").jsonPrimitive.content)
        assertEquals("5", tables.getValue("occupied").jsonPrimitive.content)
        assertEquals("1", tables.getValue("unknown").jsonPrimitive.content)
    }

    @Test
    fun a_kotlin_only_network_cause_is_not_reported_as_a_camera_fault() {
        listOf(
            HeartbeatStreamStatus.NO_NETWORK to "no_network",
            HeartbeatStreamStatus.WRONG_NETWORK to "wrong_network",
        ).forEach { (status, wire) ->
            val root = Json.parseToJsonElement(encode(minimal(status))).jsonObject
            assertEquals(wire, root.getValue("stream").jsonObject.getValue("status").jsonPrimitive.content)
        }
    }

    @Test
    fun every_wire_status_parses_back_to_the_same_enum() {
        HeartbeatStreamStatus.entries.forEach { status ->
            assertEquals(status, HeartbeatStreamStatus.fromWire(status.wire))
        }
        assertEquals(HeartbeatStreamStatus.CLOSED, HeartbeatStreamStatus.fromWire("closed"))
        assertEquals(null, HeartbeatStreamStatus.fromWire("live"))
    }

    @Test
    fun a_last_error_is_emitted_only_when_set_and_is_not_idle() {
        val payload = minimal(HeartbeatStreamStatus.TIMEOUT).let { base ->
            base.copy(stream = base.stream.copy(lastError = HeartbeatStreamStatus.TIMEOUT))
        }
        val root = Json.parseToJsonElement(encode(payload)).jsonObject

        assertEquals(
            "timeout",
            root.getValue("stream").jsonObject.getValue("last_error").jsonPrimitive.content,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun idle_is_rejected_as_a_last_error() {
        HeartbeatStream(
            status = HeartbeatStreamStatus.IDLE,
            lastError = HeartbeatStreamStatus.IDLE,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_blank_device_id_is_rejected_before_the_request_is_sent() {
        HeartbeatPayload(
            deviceId = " ",
            sessionId = "3f0c9a1e",
            sentAtEpochMs = 0L,
            stream = HeartbeatStream(HeartbeatStreamStatus.CONNECTED),
            device = HeartbeatDevice(HeartbeatDeviceProfile.BALANCED),
            runtime = HeartbeatRuntime(modelReady = true),
        )
    }

    @Test
    fun the_encoder_never_contains_a_rtsp_url_or_a_credential_field() {
        val payload = minimal().copy(
            appVersion = "0.1.0",
            configVersion = 4L,
            network = HeartbeatNetwork(HeartbeatTransport.WIFI, sameLanAsCamera = true),
            inference = HeartbeatInference(fps = 2.8, p50Ms = 38.0, p95Ms = 61.0),
            queue = HeartbeatQueue(pending = 12, oldestAgeSeconds = 95.0),
        )
        val text = encode(payload).lowercase()

        listOf("rtsp", "password", "token", "camera_ip", "ssid", "frame(", "image").forEach { banned ->
            assertFalse("heartbeat must not contain $banned", text.contains(banned))
        }
    }

    @Test
    fun optional_blocks_are_emitted_when_measured() {
        val payload = minimal().copy(
            network = HeartbeatNetwork(HeartbeatTransport.WIFI, sameLanAsCamera = null),
            queue = HeartbeatQueue(pending = 0),
        )
        val root = Json.parseToJsonElement(encode(payload)).jsonObject

        assertEquals("wifi", root.getValue("network").jsonObject.getValue("transport").jsonPrimitive.content)
        assertFalse(root.getValue("network").jsonObject.containsKey("same_lan_as_camera"))
        assertEquals("0", root.getValue("queue").jsonObject.getValue("pending").jsonPrimitive.content)
    }
}
