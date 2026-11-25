package id.warung.monitor.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A revision row joined with no other table; kept for explicit read results. */
data class TableConfigRevisionRow(
    val cameraId: String,
    val revision: Long,
    val configJson: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val aspectRatio: String,
    val createdAt: Long,
)

@Dao
interface TableConfigRevisionDao {

    @Query(
        """
        SELECT camera_id AS cameraId,
               revision,
               config_json AS configJson,
               frame_width AS frameWidth,
               frame_height AS frameHeight,
               aspect_ratio AS aspectRatio,
               created_at AS createdAt
        FROM table_config_revisions
        WHERE camera_id = :cameraId
        ORDER BY revision DESC
        LIMIT 1
        """,
    )
    fun observeActive(cameraId: String): Flow<TableConfigRevisionRow?>

    @Query(
        """
        SELECT camera_id AS cameraId,
               revision,
               config_json AS configJson,
               frame_width AS frameWidth,
               frame_height AS frameHeight,
               aspect_ratio AS aspectRatio,
               created_at AS createdAt
        FROM table_config_revisions
        WHERE camera_id = :cameraId
        ORDER BY revision DESC
        LIMIT 1
        """,
    )
    suspend fun active(cameraId: String): TableConfigRevisionRow?

    @Query("SELECT COALESCE(MAX(revision), 0) FROM table_config_revisions WHERE camera_id = :cameraId")
    suspend fun maxRevision(cameraId: String): Long

    @Insert
    suspend fun insert(row: TableConfigRevisionEntity): Long

    @Query(
        """
        DELETE FROM table_config_revisions
        WHERE camera_id = :cameraId
          AND revision NOT IN (
            SELECT revision FROM table_config_revisions
            WHERE camera_id = :cameraId
            ORDER BY revision DESC
            LIMIT :keep
          )
        """,
    )
    suspend fun prune(cameraId: String, keep: Int)

    @Query("SELECT COUNT(*) FROM table_config_revisions WHERE camera_id = :cameraId")
    suspend fun count(cameraId: String): Int

    @Query("SELECT * FROM table_config_revisions WHERE camera_id = :cameraId ORDER BY revision")
    suspend fun history(cameraId: String): List<TableConfigRevisionEntity>

    /**
     * Inserts the next revision and prunes history in one transaction.
     *
     * The unique index on (camera_id, revision) makes a concurrent duplicate fail
     * loudly instead of silently overwriting an older map.
     */
    @Transaction
    suspend fun insertNextRevision(
        cameraId: String,
        configJson: String,
        frameWidth: Int,
        frameHeight: Int,
        aspectRatio: String,
        createdAt: Long,
        keep: Int,
    ): TableConfigRevisionEntity {
        require(keep >= 1) { "keep must be at least 1" }
        val revision = maxRevision(cameraId) + 1
        val row = TableConfigRevisionEntity(
            cameraId = cameraId,
            revision = revision,
            configJson = configJson,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            aspectRatio = aspectRatio,
            createdAt = createdAt,
        )
        insert(row)
        prune(cameraId, keep)
        return row
    }
}