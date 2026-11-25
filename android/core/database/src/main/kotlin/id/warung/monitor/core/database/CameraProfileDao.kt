package id.warung.monitor.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraProfileDao {

    @Query("SELECT * FROM camera_profiles ORDER BY label COLLATE NOCASE, id")
    fun observeAll(): Flow<List<CameraProfileEntity>>

    @Query("SELECT * FROM camera_profiles ORDER BY label COLLATE NOCASE, id")
    suspend fun all(): List<CameraProfileEntity>

    @Query("SELECT * FROM camera_profiles WHERE id = :id")
    suspend fun find(id: String): CameraProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: CameraProfileEntity)

    @Query("UPDATE camera_profiles SET last_opened_at = :openedAt WHERE id = :id")
    suspend fun markOpened(id: String, openedAt: Long)

    @Query("DELETE FROM camera_profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)

    /**
     * Removes the profile and every table revision it owns.
     *
     * Both statements run in one transaction so a deleted camera cannot leave
     * orphaned calibration rows that would resurface if the id were reused.
     */
    @Transaction
    suspend fun deleteWithRevisions(id: String) {
        deleteProfile(id)
        deleteRevisions(id)
    }

    @Query("DELETE FROM table_config_revisions WHERE camera_id = :cameraId")
    suspend fun deleteRevisions(cameraId: String)

    @Query("SELECT COUNT(*) FROM camera_profiles")
    suspend fun count(): Int
}