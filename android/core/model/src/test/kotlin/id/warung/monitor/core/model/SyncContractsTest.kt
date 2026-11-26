package id.warung.monitor.core.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Kotlin encoder must produce documents the shared schemas already accept.
 *
 * These tests intentionally validate against `contracts/schemas`, not against
 * a Kotlin copy: the server will use the same documents.
 */
class SyncContractsTest {

    private val mapper = ObjectMapper()

    @Test
    fun a_table_status_payload_matches_the_shared_schema() {
        val payload = TableStatusPayload(
            tableId = "meja-01",
            status = SyncTableStatus.OCCUPIED,
            sinceEpochMs = 1_795_584_660_000L,
            confidence = 0.87654,
            personsInRegion = 1,
        )

        val json = TableStatusJson.encode(payload)

        assertEquals(emptyList<String>(), validate("table-status", json))
        assertTrue("confidence is rounded to four decimals", json.contains("0.8765"))
        // The encoder quantises confidence, so the round trip returns the
        // rounded value rather than the input.
        assertEquals(payload.copy(confidence = 0.8765), TableStatusJson.decode(json))
    }

    @Test
    fun an_unknown_status_stays_unknown_on_the_wire() {
        val payload = TableStatusPayload(
            tableId = "meja-02",
            status = SyncTableStatus.UNKNOWN,
            sinceEpochMs = 1_795_584_662_000L,
        )

        val json = TableStatusJson.encode(payload)

        assertTrue(json.contains("\"status\":\"unknown\""))
        assertTrue("absent optional fields must stay absent", !json.contains("confidence"))
        assertEquals(emptyList<String>(), validate("table-status", json))
    }

    @Test
    fun a_sync_request_matches_the_shared_schema() {
        val events = listOf(
            TableStatusJson.encode(
                TableStatusPayload("meja-01", SyncTableStatus.OCCUPIED, 1_795_584_660_000L),
            ),
            TableStatusJson.encode(
                TableStatusPayload("meja-02", SyncTableStatus.UNKNOWN, 1_795_584_662_000L),
            ),
        )
        val envelope = SyncRequestEnvelope(
            deviceId = "android-warung-01",
            seq = 42,
            sentAtEpochMs = 1_795_584_665_000L,
            events = events,
        )

        val json = SyncRequestJson.encode(envelope)

        assertEquals(emptyList<String>(), validate("sync-request", json))
        assertTrue("events are embedded as objects, not strings", !json.contains("\\\""))
    }

    @Test
    fun a_sync_ack_from_the_fixture_is_decoded() {
        val ack = SyncAckJson.decode(
            """
            {
              "device_id": "android-warung-01",
              "acked_seq": 42,
              "server_time": "2026-11-25T05:31:06Z",
              "duplicates": 1
            }
            """.trimIndent(),
        )

        assertEquals("android-warung-01", ack.deviceId)
        assertEquals(42L, ack.ackedSeq)
        assertEquals(1, ack.duplicates)
        assertEquals(1_795_584_666_000L, ack.serverTimeEpochMs)
    }

    @Test
    fun an_unknown_status_string_is_rejected_by_the_decoder() {
        val json = TableStatusJson.encode(
            TableStatusPayload("meja-01", SyncTableStatus.AVAILABLE, 1_795_584_660_000L),
        ).replace("\"available\"", "\"empty\"")

        try {
            TableStatusJson.decode(json)
            fail("an unknown status must not be decoded")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("unknown table status"))
        }
    }

    @Test
    fun optional_payload_fields_can_be_absent() {
        val decoded = TableStatusJson.decode(
            """{"table_id":"meja-09","status":"available","since":"2026-11-25T05:31:00Z"}""",
        )

        assertNull(decoded.confidence)
        assertNull(decoded.personsInRegion)
    }

    @Test
    fun timestamps_are_always_whole_seconds_in_utc() {
        assertEquals("2026-11-25T05:31:00Z", UtcTimestamps.format(1_795_584_660_999L))
        assertEquals(1_795_584_660_000L, UtcTimestamps.parse("2026-11-25T05:31:00Z"))
    }

    @Test
    fun a_batch_over_one_hundred_events_is_rejected_before_the_wire() {
        val event = TableStatusJson.encode(
            TableStatusPayload("meja-01", SyncTableStatus.AVAILABLE, 1L),
        )

        try {
            SyncRequestEnvelope("android-warung-01", 1L, 1L, List(101) { event })
            fail("batch size must be bounded")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("100"))
        }
    }

    private fun validate(contract: String, json: String): List<String> {
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
            builder.schemaLoaders { loaders ->
                loaders.schemas { uri ->
                    schemaDocuments().firstOrNull { it.first == uri }?.second
                }
            }
        }
        val validators = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
        val schema: JsonSchema = factory.getSchema(
            SchemaLocation.of("https://cctv-stream.invalid/schemas/$contract.schema.json"),
            validators,
        )
        return schema.validate(mapper.readTree(json)).map { it.message }.sorted()
    }

    private fun schemaDocuments(): List<Pair<String, String>> =
        schemaDirectory()
            .listFiles { file -> file.name.endsWith(".schema.json") }
            ?.sortedBy { it.name }
            ?.map { file ->
                val document = mapper.readTree(file)
                document.get("\$id").asText() to file.readText()
            }
            ?: emptyList()

    private fun schemaDirectory(): File {
        val configured = System.getProperty("contracts.schemas")
            ?: error("contracts.schemas system property is missing; run through Gradle")
        val file = File(configured)
        check(file.isDirectory) { "${file.absolutePath} is not a directory" }
        return file
    }
}
