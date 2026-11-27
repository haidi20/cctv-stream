package id.warung.monitor.sync

import id.warung.monitor.core.model.HeartbeatDevice
import id.warung.monitor.core.model.HeartbeatDeviceProfile
import id.warung.monitor.core.model.HeartbeatInference
import id.warung.monitor.core.model.HeartbeatJson
import id.warung.monitor.core.model.HeartbeatNetwork
import id.warung.monitor.core.model.HeartbeatPayload
import id.warung.monitor.core.model.HeartbeatQueue
import id.warung.monitor.core.model.HeartbeatRuntime
import id.warung.monitor.core.model.HeartbeatStream
import id.warung.monitor.core.model.HeartbeatStreamStatus
import id.warung.monitor.core.model.HeartbeatThermalStatus
import id.warung.monitor.core.model.HeartbeatTransport
import id.warung.monitor.core.model.TableSummary
import id.warung.monitor.feature.home.domain.EnginePhase
import id.warung.monitor.feature.home.domain.EngineStatus
import id.warung.monitor.feature.home.domain.StreamErrorKind

/**
 * Facts the heartbeat needs that do not come from the Rust health snapshot.
 *
 * Everything here is already a wire-safe value: no RTSP URL, IP, SSID, token,
 * or coordinate ever enters this type, so it cannot leak through the payload.
 */
data class HeartbeatDeviceFacts(
    val deviceId: String,
    val sessionId: String,
    val appVersion: String? = null,
    val configVersion: Long? = null,
    val profile: HeartbeatDeviceProfile = HeartbeatDeviceProfile.BALANCED,
    val ramFreeMb: Long? = null,
    val batteryTempC: Double? = null,
    val thermalStatus: HeartbeatThermalStatus? = null,
    val storageFreeMb: Long? = null,
    val transport: HeartbeatTransport? = null,
    val sameLanAsCamera: Boolean? = null,
    val inference: HeartbeatInference? = null,
    val queue: HeartbeatQueue? = null,
)

/**
 * Builds `POST /v1/heartbeat` payloads from the same snapshot the Home screen
 * reads, so a dead stream cannot be reported as a healthy one.
 *
 * Pure and injectable: `nowMillis` and the facts are passed in, which keeps the
 * test free of Android and lets it assert the exact document.
 */
object HeartbeatBuilder {

    fun build(
        status: EngineStatus,
        facts: HeartbeatDeviceFacts,
        nowMillis: Long,
    ): HeartbeatPayload {
        val stream = streamBlock(status)
        return HeartbeatPayload(
            deviceId = facts.deviceId,
            sessionId = facts.sessionId,
            sentAtEpochMs = nowMillis,
            appVersion = facts.appVersion,
            configVersion = facts.configVersion,
            stream = stream,
            network = HeartbeatNetwork(facts.transport, facts.sameLanAsCamera),
            inference = facts.inference ?: status.rustInference(),
            device = HeartbeatDevice(
                profile = facts.profile,
                ramFreeMb = facts.ramFreeMb,
                batteryTempC = facts.batteryTempC,
                thermalStatus = facts.thermalStatus,
                storageFreeMb = facts.storageFreeMb,
            ),
            runtime = HeartbeatRuntime(
                modelReady = status.modelReady,
                rssKb = status.rssKb,
                framesDropped = status.framesDropped,
            ),
            queue = facts.queue,
            // Only a snapshot with configured tables can report counts; an
            // unavailable engine has no counts, and zeroes would look like an
            // empty restaurant.
            tables = status.summary,
        )
    }

    /**
     * Maps engine state to the wire enum.
     *
     * `Unavailable` means the library never loaded, which is `error`, not
     * `idle`: an idle engine at least exists and can be started.
     */
    fun streamBlock(status: EngineStatus): HeartbeatStream {
        val statusWire = status.wireStatus()
        // `last_error` must be the same enum and is omitted while live. `idle`
        // is not an error either, so it is dropped here instead of failing the
        // payload's init block later.
        val lastError = when (statusWire) {
            HeartbeatStreamStatus.CONNECTED,
            HeartbeatStreamStatus.IDLE,
            -> null
            else -> statusWire
        }
        return HeartbeatStream(
            status = statusWire,
            lastError = lastError,
        )
    }

    /**
     * Inference latency Rust measured itself, used when no Android-side fact
     * overrides it. `null` (not zero) when the engine has never inferred, so
     * the dashboard can tell "not measured" from "measured 0 ms".
     */
    private fun EngineStatus.rustInference(): HeartbeatInference? {
        val p50 = inferenceP50Ms ?: return null
        val p95 = inferenceP95Ms ?: return null
        if (p50 == 0L && p95 == 0L) return null
        return HeartbeatInference(p50Ms = p50.toDouble(), p95Ms = p95.toDouble())
    }

    private fun EngineStatus.wireStatus(): HeartbeatStreamStatus = when (phase) {
        EnginePhase.Live -> HeartbeatStreamStatus.CONNECTED
        EnginePhase.Connecting -> HeartbeatStreamStatus.CONNECTING
        EnginePhase.Idle -> if (available) {
            HeartbeatStreamStatus.IDLE
        } else {
            HeartbeatStreamStatus.ERROR
        }
        EnginePhase.Failed -> failure?.wire() ?: HeartbeatStreamStatus.ERROR
    }

    private fun StreamErrorKind.wire(): HeartbeatStreamStatus = when (this) {
        StreamErrorKind.NoNetwork -> HeartbeatStreamStatus.NO_NETWORK
        StreamErrorKind.WrongNetwork -> HeartbeatStreamStatus.WRONG_NETWORK
        StreamErrorKind.Unreachable -> HeartbeatStreamStatus.UNREACHABLE
        StreamErrorKind.Timeout -> HeartbeatStreamStatus.TIMEOUT
        StreamErrorKind.AuthFailed -> HeartbeatStreamStatus.AUTH_FAILED
        StreamErrorKind.Protocol -> HeartbeatStreamStatus.PROTOCOL
        StreamErrorKind.Closed -> HeartbeatStreamStatus.CLOSED
        StreamErrorKind.Unknown -> HeartbeatStreamStatus.ERROR
    }
}
