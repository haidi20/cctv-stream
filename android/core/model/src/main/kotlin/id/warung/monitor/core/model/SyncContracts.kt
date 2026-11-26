package id.warung.monitor.core.model

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Wire values for the `status` field of `contracts/schemas/table-status.schema.json`.
 *
 * The enum exists so Kotlin cannot invent a fourth status: the server and Rust
 * only know these three strings, and `unknown` must survive the trip as itself.
 */
enum class SyncTableStatus(val wire: String) {
    AVAILABLE("available"),
    OCCUPIED("occupied"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String): SyncTableStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One status transition as it appears inside a sync request.
 *
 * [sinceEpochMs] is already wall-clock time: the native `since_ms` is monotonic
 * since engine start, so the caller must add the session anchor before building
 * this payload (`docs/data-contracts.md` section 2).
 */
data class TableStatusPayload(
    val tableId: String,
    val status: SyncTableStatus,
    val sinceEpochMs: Long,
    val confidence: Double? = null,
    val personsInRegion: Long? = null,
) {
    init {
        require(tableId.isNotBlank()) { "table_id must not be blank" }
        require(tableId.length <= MAX_TABLE_ID) { "table_id is too long" }
        require(sinceEpochMs >= 0) { "since must not be negative" }
        confidence?.let { require(it in 0.0..1.0) { "confidence must be within 0..1" } }
        personsInRegion?.let {
            require(it in 0..MAX_PERSONS) { "persons_in_region is out of range" }
        }
    }

    companion object {
        const val MAX_TABLE_ID: Int = 64
        const val MAX_PERSONS: Long = 4_294_967_295L
    }
}

/** RFC 3339 UTC helpers; the contract forbids local offsets. */
object UtcTimestamps {

    /** Whole-second UTC timestamp, e.g. `2026-11-25T05:31:00Z`. */
    fun format(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).toString()

    fun parse(value: String): Long = Instant.parse(value).toEpochMilli()
}

/**
 * JSON codec for a single table-status event.
 *
 * Optional fields are omitted rather than written as zero: a missing confidence
 * means "the model did not report one", which is different from a real 0.0.
 */
object TableStatusJson {

    private val compact = Json

    fun encode(payload: TableStatusPayload): String {
        val root = buildJsonObject {
            put("table_id", payload.tableId)
            put("status", payload.status.wire)
            put("since", UtcTimestamps.format(payload.sinceEpochMs))
            payload.confidence?.let { put("confidence", roundToFourDecimals(it)) }
            payload.personsInRegion?.let { put("persons_in_region", it) }
        }
        return compact.encodeToString(root)
    }

    fun decode(text: String): TableStatusPayload {
        val root = compact.parseToJsonElement(text).jsonObject
        val status = root.getValue("status").jsonPrimitive.content
        return TableStatusPayload(
            tableId = root.getValue("table_id").jsonPrimitive.content,
            status = requireNotNull(SyncTableStatus.fromWire(status)) {
                "unknown table status: $status"
            },
            sinceEpochMs = UtcTimestamps.parse(root.getValue("since").jsonPrimitive.content),
            confidence = root["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            personsInRegion = root["persons_in_region"]?.jsonPrimitive?.content?.toLongOrNull(),
        )
    }

    private fun roundToFourDecimals(value: Double): Double =
        kotlin.math.round(value * 10_000.0) / 10_000.0
}

/**
 * Envelope for `POST /v1/sync`.
 *
 * Events stay as raw JSON strings: the outbox stores exactly the document that
 * was validated when the event was written, and the batch must not re-encode it
 * differently. The same reason `TableConfigJson` keeps its own encoder.
 */
data class SyncRequestEnvelope(
    val deviceId: String,
    val seq: Long,
    val sentAtEpochMs: Long,
    val events: List<String>,
) {
    init {
        require(deviceId.isNotBlank()) { "device_id must not be blank" }
        require(deviceId.length <= MAX_DEVICE_ID) { "device_id is too long" }
        require(seq >= 0) { "seq must not be negative" }
        require(events.isNotEmpty()) { "a sync request needs at least one event" }
        require(events.size <= MAX_EVENTS) { "batch exceeds $MAX_EVENTS events" }
    }

    companion object {
        const val MAX_DEVICE_ID: Int = 64
        const val MAX_EVENTS: Int = 100
    }
}

object SyncRequestJson {

    private val compact = Json

    fun encode(envelope: SyncRequestEnvelope): String = compact.encodeToString(
        buildJsonObject {
            put("device_id", envelope.deviceId)
            put("seq", envelope.seq)
            put("sent_at", UtcTimestamps.format(envelope.sentAtEpochMs))
            put(
                "events",
                JsonArray(
                    envelope.events.map { event -> compact.parseToJsonElement(event) },
                ),
            )
        },
    )
}

/** Successful response to `POST /v1/sync`. */
data class SyncAck(
    val deviceId: String,
    val ackedSeq: Long,
    val serverTimeEpochMs: Long,
    val duplicates: Int,
)

object SyncAckJson {

    private val lenient = Json { ignoreUnknownKeys = true }

    fun decode(text: String): SyncAck {
        val root = lenient.parseToJsonElement(text).jsonObject
        return SyncAck(
            deviceId = root.getValue("device_id").jsonPrimitive.content,
            ackedSeq = root.getValue("acked_seq").jsonPrimitive.content.toLong(),
            serverTimeEpochMs = UtcTimestamps.parse(
                root.getValue("server_time").jsonPrimitive.content,
            ),
            duplicates = root.getValue("duplicates").jsonPrimitive.content.toInt(),
        )
    }
}
