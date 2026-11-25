package id.warung.monitor.data

import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.feature.home.domain.EnginePhase
import id.warung.monitor.feature.home.domain.EngineStatus
import id.warung.monitor.feature.home.domain.EngineStatusSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Demo numbers for the built-in `CameraSource.Demo` profile.
 *
 * Deterministic enough for screenshots (seeded from the camera id) but still
 * changes over time so the UI shows live-looking counts. Only used for the
 * Demo camera; a real RTSP profile always reads the Rust engine.
 */
@Singleton
class DemoEngineStatusSource @Inject constructor() : EngineStatusSource {

    private val rng = Random(7)
    private val startMs = System.currentTimeMillis()
    private var lastTick = 0L
    private var available = 8
    private var occupied = 4
    private var unknown = 0

    override suspend fun read(camera: CameraProfile?): EngineStatus {
        if (camera?.source !is CameraSource.Demo) {
            return EngineStatus.Unavailable
        }
        val elapsed = System.currentTimeMillis() - startMs
        val tick = elapsed / 3_000L
        if (tick != lastTick) {
            lastTick = tick
            unknown = rng.nextInt(0, 2)
            occupied = rng.nextInt(0, 8 - unknown + 1)
            available = 12 - occupied - unknown
        }
        // Small delay keeps the poll loop from spinning on a broken clock.
        delay(0)
        return EngineStatus(
            available = true,
            cameraId = camera.id.value,
            phase = EnginePhase.Live,
            framesIn = 1L,
            tables = 12,
            availableCount = available,
            occupiedCount = occupied,
            unknownCount = unknown,
            modelReady = true,
            demo = true,
        )
    }
}