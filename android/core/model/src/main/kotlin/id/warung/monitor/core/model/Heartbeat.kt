package id.warung.monitor.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wire values for `stream.status` in `contracts/schemas/heartbeat.schema.json`.
 *
 * The list is closed on purpose: the dashboard groups devices by these strings,
 * and `no_network`/`wrong_network` are Kotlin-only causes that must not be
 * reported as a camera fault.
 */
enum class HeartbeatStreamStatus(val wire: String) {
    IDLE("idle"),
    CONNECTED("connected"),
    CONNECTING("connecting"),
    UNREACHABLE("unreachable"),
    TIMEOUT("timeout"),
    AUTH_FAILED("auth_failed"),
    PROTOCOL("protocol"),
    CLOSED("closed"),
    ERROR("error"),
    NO_NETWORK("no_network"),
    WRONG_NETWORK("wrong_network"),
    ;

    companion object {
        fun fromWire(value: String): HeartbeatStreamStatus? =
            entries.firstOrNull { it.wire == value }
    }
}

/** `device.profile` from `device-compatibility.md` section 5. */
enum class HeartbeatDeviceProfile(val wire: String) {
    FULL("full"),
    BALANCED("balanced"),
    LITE("lite"),
    BLOCKED("blocked"),
}

/** `network.transport`; absent means the device itself lost connectivity. */
enum class HeartbeatTransport(val wire: String) {
    WIFI("wifi"),
    CELLULAR("cellular"),
    ETHERNET("ethernet"),
    NONE("none"),
    UNKNOWN("unknown"),
}

/** `device.thermal_status`, using Android's `PowerManager` names verbatim. */
enum class HeartbeatThermalStatus(val wire: String) {
    NONE("NONE"),
    LIGHT("LIGHT"),
    MODERATE("MODERATE"),
    SEVERE("SEVERE"),
    CRITICAL("CRITICAL"),
    EMERGENCY("EMERGENCY"),
    SHUTDOWN("SHUTDOWN"),
}

/**
 * `stream` block.
 *
 * [lastError] is only meaningful while the stream is not live; `idle` is not an
 * error, so the init block rejects it instead of letting the schema fail later.
 */
data class HeartbeatStream(
    val status: HeartbeatStreamStatus,
    val fpsIn: Double? = null,
    val lastFrameAgeSeconds: Double? = null,
    val reconnects1h: Long? = null,
    val lastError: HeartbeatStreamStatus? = null,
    val lastErrorAgeSeconds: Double? = null,
) {
    init {
        require(fpsIn == null || fpsIn >= 0) { "fps_in must not be negative" }
        require(lastFrameAgeSeconds == null || lastFrameAgeSeconds >= 0) {
            "last_frame_age_s must not be negative"
        }
        require(reconnects1h == null || reconnects1h in 0..MAX_U32) {
            "reconnects_1h is out of range"
        }
        require(lastError != HeartbeatStreamStatus.IDLE) { "idle is not an error" }
        require(lastErrorAgeSeconds == null || lastErrorAgeSeconds >= 0) {
            "last_error_age_s must not be negative"
        }
    }

    companion object {
        /** `reconnects_1h` is capped by the schema at an unsigned 32-bit value. */
        const val MAX_U32: Long = 4_294_967_295L
    }
}

data class HeartbeatNetwork(
    val transport: HeartbeatTransport? = null,
    val sameLanAsCamera: Boolean? = null,
) {
    val hasAnyValue: Boolean get() = transport != null || sameLanAsCamera != null
}

data class HeartbeatInference(
    val fps: Double? = null,
    val p50Ms: Double? = null,
    val p95Ms: Double? = null,
) {
    init {
        require(fps == null || fps >= 0) { "inference fps must not be negative" }
        require(p50Ms == null || p50Ms >= 0) { "p50_ms must not be negative" }
        require(p95Ms == null || p95Ms >= 0) { "p95_ms must not be negative" }
    }

    val hasAnyValue: Boolean get() = fps != null || p50Ms != null || p95Ms != null
}

data class HeartbeatDevice(
    val profile: HeartbeatDeviceProfile,
    val ramFreeMb: Long? = null,
    val batteryTempC: Double? = null,
    val thermalStatus: HeartbeatThermalStatus? = null,
    val storageFreeMb: Long? = null,
) {
    init {
        require(ramFreeMb == null || ramFreeMb >= 0) { "ram_free_mb must not be negative" }
        require(batteryTempC == null || batteryTempC in MIN_TEMP_C..MAX_TEMP_C) {
            "battery_temp_c is out of range"
        }
        require(storageFreeMb == null || storageFreeMb >= 0) {
            "storage_free_mb must not be negative"
        }
    }

    companion object {
        /** Schema range for `battery_temp_c`; outside it the value is a bug. */
        const val MIN_TEMP_C: Double = -40.0
        const val MAX_TEMP_C: Double = 120.0
    }
}

data class HeartbeatRuntime(
    val modelReady: Boolean,
    val warmupMs: Long? = null,
    val modelChecksumOk: Boolean? = null,
    /**
     * Resident set size of the Rust process in KiB.
     *
     * Native-side RSS, not the whole app PSS: that is what a soak needs to
     * separate a leak in the detector/session from a leak in Compose.
     */
    val rssKb: Long? = null,
    /** Frames the rate sampler skipped instead of queueing. */
    val framesDropped: Long? = null,
) {
    init {
        require(warmupMs == null || warmupMs >= 0) { "warmup_ms must not be negative" }
        require(rssKb == null || rssKb >= 0) { "rss_kb must not be negative" }
        require(framesDropped == null || framesDropped >= 0) {
            "frames_dropped must not be negative"
        }
    }

    val hasAnyValue: Boolean
        get() = warmupMs != null || modelChecksumOk != null || rssKb != null || framesDropped != null
}

data class HeartbeatQueue(
    val pending: Long? = null,
    val oldestAgeSeconds: Double? = null,
) {
    init {
        require(pending == null || pending >= 0) { "queue.pending must not be negative" }
        require(oldestAgeSeconds == null || oldestAgeSeconds >= 0) {
            "queue.oldest_age_s must not be negative"
        }
    }

    val hasAnyValue: Boolean get() = pending != null || oldestAgeSeconds != null
}

/**
 * One heartbeat document.
 *
 * Metadata only: there is deliberately no field for frames, images, RTSP URLs,
 * coordinates, SSIDs, or credentials, so none can leak by accident.
 */
data class HeartbeatPayload(
    val deviceId: String,
    val sessionId: String,
    val sentAtEpochMs: Long,
    val stream: HeartbeatStream,
    val device: HeartbeatDevice,
    val runtime: HeartbeatRuntime,
    val appVersion: String? = null,
    val configVersion: Long? = null,
    val network: HeartbeatNetwork? = null,
    val inference: HeartbeatInference? = null,
    val queue: HeartbeatQueue? = null,
    val tables: TableSummary? = null,
) {
    init {
        require(deviceId.isNotBlank()) { "device_id must not be blank" }
        require(deviceId.length <= MAX_ID) { "device_id is too long" }
        require(sessionId.isNotBlank()) { "session_id must not be blank" }
        require(sessionId.length <= MAX_ID) { "session_id is too long" }
        require(sentAtEpochMs >= 0) { "sent_at must not be negative" }
        require(appVersion == null || appVersion.isNotBlank()) { "app_version must not be blank" }
        require(appVersion == null || appVersion.length <= MAX_APP_VERSION) {
            "app_version is too long"
        }
        require(configVersion == null || configVersion >= 0) {
            "config_version must not be negative"
        }
    }

    companion object {
        const val MAX_ID: Int = 64
        const val MAX_APP_VERSION: Int = 32
    }
}

/**
 * Encoder for `POST /v1/heartbeat`.
 *
 * Optional blocks are omitted, not written as `{}`. A missing field means "not
 * measured"; a zero would be read as a real measurement and skew the dashboard.
 */
object HeartbeatJson {

    private val compact = Json

    fun encode(payload: HeartbeatPayload): String = compact.encodeToString(
        buildJsonObject {
            put("device_id", payload.deviceId)
            put("session_id", payload.sessionId)
            put("sent_at", UtcTimestamps.format(payload.sentAtEpochMs))
            payload.appVersion?.let { put("app_version", it) }
            payload.configVersion?.let { put("config_version", it) }
            put("stream", streamJson(payload.stream))
            payload.network?.takeIf { it.hasAnyValue }?.let { network ->
                put(
                    "network",
                    buildJsonObject {
                        network.transport?.let { put("transport", it.wire) }
                        network.sameLanAsCamera?.let { put("same_lan_as_camera", it) }
                    },
                )
            }
            payload.inference?.takeIf { it.hasAnyValue }?.let { inference ->
                put(
                    "inference",
                    buildJsonObject {
                        inference.fps?.let { put("fps", it) }
                        inference.p50Ms?.let { put("p50_ms", it) }
                        inference.p95Ms?.let { put("p95_ms", it) }
                    },
                )
            }
            put(
                "device",
                buildJsonObject {
                    put("profile", payload.device.profile.wire)
                    payload.device.ramFreeMb?.let { put("ram_free_mb", it) }
                    payload.device.batteryTempC?.let { put("battery_temp_c", it) }
                    payload.device.thermalStatus?.let { put("thermal_status", it.wire) }
                    payload.device.storageFreeMb?.let { put("storage_free_mb", it) }
                },
            )
            put(
                "runtime",
                buildJsonObject {
                    put("model_ready", payload.runtime.modelReady)
                    payload.runtime.warmupMs?.let { put("warmup_ms", it) }
                    payload.runtime.modelChecksumOk?.let { put("model_checksum_ok", it) }
                    payload.runtime.rssKb?.let { put("rss_kb", it) }
                    payload.runtime.framesDropped?.let { put("frames_dropped", it) }
                },
            )
            payload.queue?.takeIf { it.hasAnyValue }?.let { queue ->
                put(
                    "queue",
                    buildJsonObject {
                        queue.pending?.let { put("pending", it) }
                        queue.oldestAgeSeconds?.let { put("oldest_age_s", it) }
                    },
                )
            }
            payload.tables?.let { tables ->
                put(
                    "tables",
                    buildJsonObject {
                        put("occupied", tables.occupied)
                        put("available", tables.available)
                        put("unknown", tables.unknown)
                    },
                )
            }
        },
    )

    private fun streamJson(stream: HeartbeatStream): JsonObject = buildJsonObject {
        put("status", stream.status.wire)
        stream.fpsIn?.let { put("fps_in", it) }
        stream.lastFrameAgeSeconds?.let { put("last_frame_age_s", it) }
        stream.reconnects1h?.let { put("reconnects_1h", it) }
        stream.lastError?.let { put("last_error", it.wire) }
        stream.lastErrorAgeSeconds?.let { put("last_error_age_s", it) }
    }
}
