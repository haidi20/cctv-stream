package id.warung.monitor.data

import id.warung.monitor.core.Engine
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.di.EngineProvider
import id.warung.monitor.feature.home.domain.EnginePhase
import id.warung.monitor.feature.home.domain.EngineStatus
import id.warung.monitor.feature.home.domain.EngineStatusSource
import id.warung.monitor.feature.home.domain.StreamErrorKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the Rust health snapshot without ever starting the engine implicitly.
 *
 * A missing `libcore_ffi.so` or a failed `health()` degrades to
 * [EngineStatus.Unavailable]; Home then keeps showing an em dash instead of
 * inventing zeroes. The engine only reports the camera that was actually
 * started, so a stale session can never be attributed to another profile.
 */
@Singleton
class RustEngineStatusSource(
    private val engine: () -> Engine?,
) : EngineStatusSource {

    @Inject
    constructor(engineProvider: EngineProvider) : this(engineProvider::engineOrNull)

    override suspend fun read(camera: CameraProfile?): EngineStatus = withContext(Dispatchers.IO) {
        val engine = engine() ?: return@withContext unavailable(camera)
        try {
            val health = engine.health()
            val phase = when (health.stream) {
                id.warung.monitor.core.StreamStatus.CONNECTED -> EnginePhase.Live
                id.warung.monitor.core.StreamStatus.CONNECTING -> EnginePhase.Connecting
                id.warung.monitor.core.StreamStatus.IDLE -> EnginePhase.Idle
                id.warung.monitor.core.StreamStatus.UNREACHABLE,
                id.warung.monitor.core.StreamStatus.TIMEOUT,
                id.warung.monitor.core.StreamStatus.AUTH_FAILED,
                id.warung.monitor.core.StreamStatus.PROTOCOL,
                id.warung.monitor.core.StreamStatus.CLOSED,
                id.warung.monitor.core.StreamStatus.ERROR,
                -> EnginePhase.Failed
            }
            EngineStatus(
                available = true,
                cameraId = health.cameraId.ifBlank { null },
                phase = phase,
                failure = health.stream.toErrorKind(),
                needsRecalibration = health.needsRecalibration,
                framesIn = health.framesIn.toLong(),
                tables = health.tables.toInt(),
                availableCount = health.tablesAvailable.toInt(),
                occupiedCount = health.tablesOccupied.toInt(),
                unknownCount = health.tablesUnknown.toInt(),
                modelReady = health.modelReady,
                demo = camera?.source is CameraSource.Demo,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            unavailable(camera)
        }
    }

    private fun unavailable(camera: CameraProfile?): EngineStatus =
        EngineStatus.Unavailable.copy(demo = camera?.source is CameraSource.Demo)

    /**
     * Exhaustive on purpose: a new Rust status must fail compilation here
     * instead of silently degrading to "Stream gagal".
     */
    private fun id.warung.monitor.core.StreamStatus.toErrorKind(): StreamErrorKind? = when (this) {
        id.warung.monitor.core.StreamStatus.UNREACHABLE -> StreamErrorKind.Unreachable
        id.warung.monitor.core.StreamStatus.TIMEOUT -> StreamErrorKind.Timeout
        id.warung.monitor.core.StreamStatus.AUTH_FAILED -> StreamErrorKind.AuthFailed
        id.warung.monitor.core.StreamStatus.PROTOCOL -> StreamErrorKind.Protocol
        id.warung.monitor.core.StreamStatus.CLOSED -> StreamErrorKind.Closed
        id.warung.monitor.core.StreamStatus.ERROR -> StreamErrorKind.Unknown
        // Live or not yet started: nothing failed, so there is no cause to show.
        id.warung.monitor.core.StreamStatus.CONNECTED,
        id.warung.monitor.core.StreamStatus.CONNECTING,
        id.warung.monitor.core.StreamStatus.IDLE,
        -> null
    }
}
