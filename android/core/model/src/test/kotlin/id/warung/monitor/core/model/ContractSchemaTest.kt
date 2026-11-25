package id.warung.monitor.core.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import com.networknt.schema.SchemaLocation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves Kotlin and Rust validate against the same documents.
 *
 * The Rust crate `contracts` owns these schemas; this test only *consumes* them.
 * It catches the failure mode a hand-written port cannot: Kotlin agreeing with
 * itself while both disagree with the contract. Nothing here is shipped in the
 * app, and no schema is copied into Kotlin.
 */
class ContractSchemaTest {

    private val mapper = ObjectMapper()

    @Test
    fun schema_directory_matches_the_embedded_contract() {
        // Guards against a stale checkout or a schema that never got committed.
        val names = schemaDirectory().listFiles { file -> file.name.endsWith(".schema.json") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

        assertEquals(
            listOf(
                "heartbeat.schema.json",
                "sync-ack.schema.json",
                "sync-request.schema.json",
                "table-config.schema.json",
                "table-status.schema.json",
            ),
            names,
        )
    }

    @Test
    fun every_valid_fixture_passes_against_the_real_schema() {
        val files = fixtureDirectory("valid")
        assertTrue("no valid fixtures found", files.isNotEmpty())

        files.forEach { file ->
            val errors = validate(fixtureContract(file.name), mapper.readTree(file))
            assertEquals("${file.name} should be valid: $errors", emptyList<String>(), errors)
        }
    }

    @Test
    fun every_invalid_fixture_is_rejected_by_the_real_schema() {
        val files = fixtureDirectory("invalid")
        assertTrue("no invalid fixtures found", files.isNotEmpty())

        files.forEach { file ->
            val errors = validate(fixtureContract(file.name), mapper.readTree(file))
            assertTrue("${file.name} should be rejected by the schema", errors.isNotEmpty())
        }
    }

    @Test
    fun encoded_table_config_is_accepted_by_the_table_config_schema() {
        // The encoder is the boundary that matters: whatever Kotlin writes is
        // what Rust and the server will parse.
        val config = TableConfigSet(
            frame = MappingFrame(1280, 720, "16:9"),
            tables = listOf(
                TableConfig(
                    id = TableId("meja-01"),
                    label = TableLabel("Meja 01"),
                    polygon = requireNotNull(
                        TablePolygon.orNull(
                            listOf(
                                NormalizedPoint(0.123456, 0.1),
                                NormalizedPoint(0.333333, 0.1),
                                NormalizedPoint(0.333333, 0.333333),
                                NormalizedPoint(0.1, 0.333333),
                            ),
                        ),
                    ),
                ),
            ),
        )

        listOf(true, false).forEach { pretty ->
            val json = TableConfigJson.encode(config, prettyPrint = pretty)
            val errors = validate("table-config", mapper.readTree(json))
            assertEquals("encoder output should be valid: $errors", emptyList<String>(), errors)
        }
    }

    @Test
    fun decoder_accepts_every_valid_fixture_the_schema_accepts() {
        // The two validators must not disagree about what a valid document is.
        fixtureDirectory("valid").forEach { file ->
            val result = TableConfigJson.decode(file.readText())
            if (file.name.startsWith("table-config") || file.name == "meja-mock.json") {
                assertTrue("${file.name}: $result", result is TableConfigDecodeResult.Success)
            }
        }
    }

    private fun fixtureContract(fileName: String): String {
        val prefix = fileName.substringBefore('.')
        return if (prefix == "meja-mock") "table-config" else prefix
    }

    private fun validate(contract: String, payload: JsonNode): List<String> =
        schema(contract).validate(payload).map { it.message }.sorted()

    private fun schema(contract: String): JsonSchema {
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
            builder.schemaLoaders { loaders ->
                loaders.schemas { uri ->
                    schemaDocuments().firstOrNull { it.first == uri }?.second
                }
            }
        }
        val validators = SchemaValidatorsConfig.builder()
            .formatAssertionsEnabled(true)
            .build()
        return factory.getSchema(SchemaLocation.of(schemaId(contract)), validators)
    }

    private fun schemaId(contract: String): String =
        "https://cctv-stream.invalid/schemas/$contract.schema.json"

    /** `$id` to document text, read from the shared `contracts/schemas` directory. */
    private fun schemaDocuments(): List<Pair<String, String>> =
        schemaDirectory()
            .listFiles { file -> file.name.endsWith(".schema.json") }
            ?.sortedBy { it.name }
            ?.map { file ->
                val document = mapper.readTree(file)
                val id = document.get("\$id").asText()
                id to file.readText()
            }
            ?: emptyList()

    private fun schemaDirectory(): File = directory("contracts.schemas")

    private fun fixtureDirectory(kind: String): List<File> =
        directory("contracts.fixtures")
            .resolve(kind)
            .listFiles { file -> file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun directory(property: String): File {
        val configured = System.getProperty(property)
            ?: error("$property system property is missing; run this test through Gradle")
        val file = File(configured)
        check(file.isDirectory) { "${file.absolutePath} is not a directory" }
        return file
    }
}