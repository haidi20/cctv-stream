package id.warung.monitor.engine

import id.warung.monitor.feature.tables.engine.TableApplyResult
import id.warung.monitor.feature.tables.engine.TableConfigSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the mapping feature to the active camera session.
 *
 * The feature layer must never reach the engine directly: the map is only valid
 * for the camera whose stream is open. [RustCameraSession] owns the mutex, the
 * engine health check, and the IO dispatcher, so this class stays a thin seam
 * that is trivial to fake in tests.
 */
@Singleton
class RustTableConfigSink @Inject constructor(
    private val session: RustCameraSession,
) : TableConfigSink {

    override suspend fun apply(cameraId: String, configJson: String): TableApplyResult =
        session.applyTables(cameraId, configJson)
}