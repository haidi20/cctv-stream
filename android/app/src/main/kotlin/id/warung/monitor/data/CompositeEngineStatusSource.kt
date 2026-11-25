package id.warung.monitor.data

import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.feature.home.domain.EngineStatus
import id.warung.monitor.feature.home.domain.EngineStatusSource

/**
 * Routes Demo cameras to the local simulator and everything else to Rust.
 *
 * This is the only place that decides which source owns the numbers; Home and
 * its tests only see [EngineStatusSource].
 */
class CompositeEngineStatusSource(
    private val demo: EngineStatusSource,
    private val rust: EngineStatusSource,
) : EngineStatusSource {

    override suspend fun read(camera: CameraProfile?): EngineStatus =
        if (camera?.source is CameraSource.Demo) demo.read(camera) else rust.read(camera)
}