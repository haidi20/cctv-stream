package id.warung.monitor.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The inference numbers this phone actually measured, not the numbers a
 * document guessed.
 *
 * One row, always [SINGLETON_ID]. It holds the values a session last ran with
 * plus the p95 latency that produced them, so the next start can reuse the
 * decision instead of repeating the same guess on every install
 * (`docs/kotlin-rust-gaps.md` G-14). The table is deliberately not per camera:
 * the engine is one session at a time on one device, so latency and thread
 * count describe the phone, not the camera.
 *
 * Nothing here is a credential or a URL, so the row may be exported in a
 * diagnostic dump as-is.
 */
@Entity(tableName = "device_settings")
data class DeviceSettingsEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SINGLETON_ID,
    /** Inference rate the last session requested, in frames per second. */
    @ColumnInfo(name = "inference_fps")
    val inferenceFps: Double,
    /** ONNX Runtime session threads the last session used. */
    @ColumnInfo(name = "intra_threads")
    val intraThreads: Int,
    /**
     * p95 inference latency measured by Rust, or null before any measurement.
     *
     * Null and zero mean different things: null is "never measured", zero is
     * "measured, but no frame was sampled". Only a positive value may tune the
     * next session.
     */
    @ColumnInfo(name = "p95_inference_ms")
    val p95InferenceMs: Long?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        /** The table holds exactly one row; this is its key. */
        const val SINGLETON_ID: Int = 1
    }
}
