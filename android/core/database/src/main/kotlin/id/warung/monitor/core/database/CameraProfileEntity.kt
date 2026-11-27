package id.warung.monitor.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A camera the user can open. One profile owns one set of table revisions.
 *
 * There is deliberately no password column. A future RTSP pipeline stores the
 * secret in Android Keystore keyed by [id], never in Room, JSON, or logs.
 */
@Entity(
    tableName = "camera_profiles",
    indices = [Index(value = ["label"])],
)
data class CameraProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "label")
    val label: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "host")
    val host: String?,
    @ColumnInfo(name = "port")
    val port: Int?,
    @ColumnInfo(name = "stream_path")
    val streamPath: String?,
    @ColumnInfo(name = "username")
    val username: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_opened_at")
    val lastOpenedAt: Long?,
) {
    companion object {
        const val SOURCE_DEMO: String = "demo"
        const val SOURCE_VIDEO: String = "video"
        const val SOURCE_RTSP: String = "rtsp"
        const val DEFAULT_ID: String = "camera-default"
    }
}