package id.warung.monitor.core.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableConfigJsonTest {

    private fun canonicalFixture(): String {
        val root = System.getProperty("contracts.fixtures")
            ?: error("contracts.fixtures system property is missing")
        return File(root, "valid/meja-mock.json").readText()
    }

    private fun decodeOk(raw: String): TableConfigSet =
        when (val result = TableConfigJson.decode(raw)) {
            is TableConfigDecodeResult.Success -> result.config
            is TableConfigDecodeResult.Failure -> error("decode failed: ${result.message}")
        }

    @Test
    fun decodes_the_canonical_fixture() {
        val config = decodeOk(canonicalFixture())
        assertEquals(2, TABLE_CONFIG_VERSION)
        assertEquals(MappingFrame(1280, 720, "16:9"), config.frame)
        assertEquals(1, config.tables.size)

        val table = config.tables.single()
        assertEquals("meja-mock", table.id.value)
        assertEquals("Meja Mock", table.label.value)
        assertEquals(4, table.polygon.points.size)
        assertEquals(0.2, table.polygon.points.first().x, 1e-12)
    }

    @Test
    fun reencoding_the_fixture_round_trips() {
        val once = TableConfigJson.encode(decodeOk(canonicalFixture()), prettyPrint = false)
        val twice = TableConfigJson.encode(decodeOk(once), prettyPrint = false)
        assertEquals(once, twice)
        assertTrue(once.contains("\"version\":2"))
        assertTrue(once.contains("\"meja-mock\""))
    }

    @Test
    fun coordinates_are_written_with_at_most_four_decimals() {
        val config = TableConfigSet(
            frame = MappingFrame(1280, 720, "16:9"),
            tables = listOf(
                TableConfig(
                    TableId("meja-01"),
                    TableLabel("Meja 01"),
                    TablePolygon.ofValidated(
                        listOf(
                            NormalizedPoint(0.123456, 0.1),
                            NormalizedPoint(0.333333, 0.1),
                            NormalizedPoint(0.333333, 0.333333),
                        ),
                    ),
                ),
            ),
        )
        val json = TableConfigJson.encode(config)
        assertTrue(json.contains("0.1235"))
        assertTrue(json.contains("0.3333"))
        assertTrue(!json.contains("0.123456"))
    }

    @Test
    fun rejects_pixel_version() {
        val result = TableConfigJson.decode("""{"version":1,"video":{"width":1,"height":1,"aspect_ratio":"16:9"},"tables":[]}""")
        assertEquals(
            TableConfigError.LEGACY_PIXEL_VERSION,
            (result as TableConfigDecodeResult.Failure).error,
        )
    }

    @Test
    fun rejects_unknown_version() {
        val result = TableConfigJson.decode("""{"version":3,"video":{"width":1,"height":1,"aspect_ratio":"16:9"},"tables":[]}""")
        assertEquals(
            TableConfigError.UNSUPPORTED_VERSION,
            (result as TableConfigDecodeResult.Failure).error,
        )
    }

    @Test
    fun rejects_duplicate_ids() {
        val raw = """
            {
              "version": 2,
              "video": {"width": 1280, "height": 720, "aspect_ratio": "16:9"},
              "tables": [
                {"id":"a","label":"A","polygon":[{"x":0.1,"y":0.1},{"x":0.3,"y":0.1},{"x":0.3,"y":0.3}]},
                {"id":"a","label":"A2","polygon":[{"x":0.1,"y":0.1},{"x":0.3,"y":0.1},{"x":0.3,"y":0.3}]}
              ]
            }
        """.trimIndent()
        assertEquals(
            TableConfigError.DUPLICATE_ID,
            (TableConfigJson.decode(raw) as TableConfigDecodeResult.Failure).error,
        )
    }

    @Test
    fun blank_label_falls_back_to_id() {
        val raw = """
            {
              "version": 2,
              "video": {"width": 1280, "height": 720, "aspect_ratio": "16:9"},
              "tables": [
                {"id":"meja-07","label":"  ","polygon":[{"x":0.1,"y":0.1},{"x":0.3,"y":0.1},{"x":0.3,"y":0.3}]}
              ]
            }
        """.trimIndent()
        val config = decodeOk(raw)
        assertEquals("meja-07", config.tables.single().label.value)
    }

    @Test
    fun rejects_out_of_range_coordinate() {
        val raw = """
            {
              "version": 2,
              "video": {"width": 1280, "height": 720, "aspect_ratio": "16:9"},
              "tables": [
                {"id":"x","label":"X","polygon":[{"x":1.2,"y":0.1},{"x":0.3,"y":0.1},{"x":0.3,"y":0.3}]}
              ]
            }
        """.trimIndent()
        assertEquals(
            TableConfigError.COORDINATE_OUT_OF_RANGE,
            (TableConfigJson.decode(raw) as TableConfigDecodeResult.Failure).error,
        )
    }

    @Test
    fun rejects_degenerate_area_when_decoding() {
        val raw = """
            {
              "version": 2,
              "video": {"width": 1280, "height": 720, "aspect_ratio": "16:9"},
              "tables": [
                {"id":"x","label":"X","polygon":[{"x":0.1,"y":0.1},{"x":0.11,"y":0.1},{"x":0.12,"y":0.1}]}
              ]
            }
        """.trimIndent()
        assertEquals(
            TableConfigError.DEGENERATE_AREA,
            (TableConfigJson.decode(raw) as TableConfigDecodeResult.Failure).error,
        )
    }

    @Test
    fun rejects_self_intersecting_when_decoding() {
        val raw = """
            {
              "version": 2,
              "video": {"width": 1280, "height": 720, "aspect_ratio": "16:9"},
              "tables": [
                {"id":"x","label":"X","polygon":[
                  {"x":0.1,"y":0.1},{"x":0.4,"y":0.4},{"x":0.4,"y":0.1},{"x":0.1,"y":0.4},{"x":0.0,"y":0.0}
                ]}
              ]
            }
        """.trimIndent()
        assertEquals(
            TableConfigError.SELF_INTERSECTING,
            (TableConfigJson.decode(raw) as TableConfigDecodeResult.Failure).error,
        )
    }
}