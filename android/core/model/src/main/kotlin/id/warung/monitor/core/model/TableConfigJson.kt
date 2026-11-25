package id.warung.monitor.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Current schema version; version 2 stores normalised coordinates. */
const val TABLE_CONFIG_VERSION: Int = 2

/** Why a table configuration could not be decoded. */
enum class TableConfigError {
    INVALID_JSON,
    LEGACY_PIXEL_VERSION,
    UNSUPPORTED_VERSION,
    INVALID_FRAME,
    INVALID_ASPECT_RATIO,
    EMPTY_ID,
    DUPLICATE_ID,
    TOO_FEW_POINTS,
    COORDINATE_OUT_OF_RANGE,
    DEGENERATE_AREA,
    SELF_INTERSECTING,
}

/** Result of decoding a configuration document. */
sealed interface TableConfigDecodeResult {
    data class Success(val config: TableConfigSet) : TableConfigDecodeResult
    data class Failure(
        val error: TableConfigError,
        val tableId: String? = null,
        val message: String,
    ) : TableConfigDecodeResult
}

/**
 * JSON codec for the `version: 2` contract in `docs/data-contracts.md`.
 *
 * Encoding rounds coordinates to four decimals before writing, so the document a
 * user saves is the document Rust will validate. Decoding rejects anything the Rust
 * loader rejects instead of repairing it silently.
 */
object TableConfigJson {

    private val pretty = Json { prettyPrint = true }
    private val compact = Json

    fun encode(config: TableConfigSet, prettyPrint: Boolean = true): String {
        val quantized = config.quantized()
        val root = buildJsonObject {
            put("version", TABLE_CONFIG_VERSION)
            put("video", buildJsonObject {
                put("width", quantized.frame.width)
                put("height", quantized.frame.height)
                put("aspect_ratio", quantized.frame.aspectRatio)
            })
            put("tables", JsonArray(quantized.tables.map { table ->
                buildJsonObject {
                    put("id", table.id.value)
                    put("label", table.label.value)
                    put("polygon", JsonArray(table.polygon.points.map { point ->
                        buildJsonObject {
                            put("x", point.x.round4())
                            put("y", point.y.round4())
                        }
                    }))
                }
            }))
        }
        return (if (prettyPrint) pretty else compact).encodeToString(JsonObject.serializer(), root)
    }

    fun decode(raw: String): TableConfigDecodeResult {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }
            .getOrElse {
                return TableConfigDecodeResult.Failure(
                    TableConfigError.INVALID_JSON,
                    message = "JSON tidak valid",
                )
            }

        val version = runCatching { root.getValue("version").jsonPrimitive.int }
            .getOrElse {
                return TableConfigDecodeResult.Failure(
                    TableConfigError.INVALID_JSON,
                    message = "Versi konfigurasi tidak ditemukan",
                )
            }

        when {
            version == 1 -> return TableConfigDecodeResult.Failure(
                TableConfigError.LEGACY_PIXEL_VERSION,
                message = "Konfigurasi versi 1 memakai piksel; migrasi manual diperlukan",
            )
            version != TABLE_CONFIG_VERSION -> return TableConfigDecodeResult.Failure(
                TableConfigError.UNSUPPORTED_VERSION,
                message = "Versi konfigurasi $version tidak didukung",
            )
        }

        val video = runCatching { root.getValue("video").jsonObject }
            .getOrElse {
                return TableConfigDecodeResult.Failure(
                    TableConfigError.INVALID_JSON,
                    message = "Bagian video tidak ditemukan",
                )
            }

        val width = runCatching { video.getValue("width").jsonPrimitive.int }.getOrNull()
        val height = runCatching { video.getValue("height").jsonPrimitive.int }.getOrNull()
        val aspectRatio = runCatching { video.getValue("aspect_ratio").jsonPrimitive.content }
            .getOrNull()

        if (width == null || height == null || width <= 0 || height <= 0) {
            return TableConfigDecodeResult.Failure(
                TableConfigError.INVALID_FRAME,
                message = "Ukuran video tidak valid",
            )
        }
        if (aspectRatio == null || parseAspectRatio(aspectRatio) == null) {
            return TableConfigDecodeResult.Failure(
                TableConfigError.INVALID_ASPECT_RATIO,
                message = "Rasio video tidak valid",
            )
        }

        val tablesElement = runCatching { root.getValue("tables").jsonArray }
            .getOrElse {
                return TableConfigDecodeResult.Failure(
                    TableConfigError.INVALID_JSON,
                    message = "Daftar meja tidak ditemukan",
                )
            }

        val seen = HashSet<String>()
        val tables = ArrayList<TableConfig>(tablesElement.size)

        for (element in tablesElement) {
            val table = element.jsonObject
            val idText = (table["id"] as? JsonPrimitive)?.content.orEmpty().trim()
            if (idText.isEmpty()) {
                return TableConfigDecodeResult.Failure(
                    TableConfigError.EMPTY_ID,
                    message = "Id meja tidak boleh kosong",
                )
            }
            if (!seen.add(idText)) {
                return TableConfigDecodeResult.Failure(
                    TableConfigError.DUPLICATE_ID,
                    tableId = idText,
                    message = "Id meja ganda: $idText",
                )
            }

            val polygonElements = runCatching { table.getValue("polygon").jsonArray }.getOrElse {
                return TableConfigDecodeResult.Failure(
                    TableConfigError.INVALID_JSON,
                    tableId = idText,
                    message = "Poligon meja $idText tidak ditemukan",
                )
            }
            if (polygonElements.size < PolygonValidator.MIN_VERTEX_COUNT) {
                return TableConfigDecodeResult.Failure(
                    TableConfigError.TOO_FEW_POINTS,
                    tableId = idText,
                    message = "Meja $idText punya ${polygonElements.size} titik; minimal 3",
                )
            }

            val points = ArrayList<NormalizedPoint>(polygonElements.size)
            for (pointElement in polygonElements) {
                val pointObject = pointElement.jsonObject
                val x = runCatching { pointObject.getValue("x").jsonPrimitive.double }.getOrNull()
                val y = runCatching { pointObject.getValue("y").jsonPrimitive.double }.getOrNull()
                val point = if (x == null || y == null) null else NormalizedPoint.orNull(x, y)
                if (point == null) {
                    return TableConfigDecodeResult.Failure(
                        TableConfigError.COORDINATE_OUT_OF_RANGE,
                        tableId = idText,
                        message = "Koordinat meja $idText harus di 0..1",
                    )
                }
                points += point
            }

            when (val validation = PolygonValidator.validate(points)) {
                is PolygonValidation.Valid -> {
                    val label = TableLabel.orFallback(
                        (table["label"] as? JsonPrimitive)?.content,
                        TableId(idText),
                    )
                    tables += TableConfig(TableId(idText), label, validation.polygon)
                }
                is PolygonValidation.Invalid -> {
                    val error = when (validation.problem) {
                        PolygonProblem.TOO_FEW_POINTS -> TableConfigError.TOO_FEW_POINTS
                        PolygonProblem.COORDINATE_OUT_OF_RANGE -> TableConfigError.COORDINATE_OUT_OF_RANGE
                        PolygonProblem.DEGENERATE_AREA -> TableConfigError.DEGENERATE_AREA
                        PolygonProblem.SELF_INTERSECTING -> TableConfigError.SELF_INTERSECTING
                    }
                    return TableConfigDecodeResult.Failure(
                        error = error,
                        tableId = idText,
                        message = "Meja $idText: ${validation.detail}",
                    )
                }
            }
        }

        return TableConfigDecodeResult.Success(
            TableConfigSet(
                frame = MappingFrame(width, height, aspectRatio),
                tables = tables,
            ),
        )
    }

    /** Convenience for callers that only need the JSON text. */
    fun encodeOrThrow(config: TableConfigSet): String = try {
        encode(config)
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("Konfigurasi meja tidak valid", error)
    }

    @Suppress("unused")
    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
}