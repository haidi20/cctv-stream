package id.warung.monitor.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One immutable revision of a camera's table map.
 *
 * `config_json` is the source of truth. The frame columns are duplicated for
 * diagnostics and for the recalibration banner, never for geometry.
 *
 * The active configuration is always the row with the highest [revision] for a
 * camera; there is no mutable `is_active` flag that could disagree with history.
 */
@Entity(
    tableName = "table_config_revisions",
    indices = [
        Index(value = ["camera_id", "revision"], unique = true),
        Index(value = ["camera_id", "created_at"]),
    ],
)
data class TableConfigRevisionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "camera_id")
    val cameraId: String,
    @ColumnInfo(name = "revision")
    val revision: Long,
    @ColumnInfo(name = "config_json")
    val configJson: String,
    @ColumnInfo(name = "frame_width")
    val frameWidth: Int,
    @ColumnInfo(name = "frame_height")
    val frameHeight: Int,
    @ColumnInfo(name = "aspect_ratio")
    val aspectRatio: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)