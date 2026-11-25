package id.warung.monitor.feature.tables.engine

/**
 * Boundary to the Rust engine.
 *
 * Implementations live in `:app` because the UniFFI bindings are generated
 * there. The feature module only needs this seam, which keeps ViewModel tests
 * free of JNA.
 *
 * Every call names the camera that owns the JSON: one engine serves one camera
 * at a time, so a sink must reject a map for a camera that is not the active
 * session instead of silently applying it to the wrong stream.
 */
fun interface TableConfigSink {
    /** Sends `setTables` JSON for [cameraId]; must never throw for a missing engine. */
    suspend fun apply(cameraId: String, configJson: String): TableApplyResult
}

/** What happened when the map was handed to the engine. */
sealed interface TableApplyResult {
    data object Applied : TableApplyResult
    data class Rejected(val reason: String) : TableApplyResult
    data class Unavailable(val reason: String) : TableApplyResult

    /** True when the map is stored locally but this engine cannot accept it. */
    data class WrongCamera(val activeCameraId: String?) : TableApplyResult
}

/** Sink used by previews and tests where no engine exists. */
object UnavailableTableConfigSink : TableConfigSink {
    override suspend fun apply(cameraId: String, configJson: String): TableApplyResult =
        TableApplyResult.Unavailable("Engine belum tersedia")
}