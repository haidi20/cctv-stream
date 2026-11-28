package id.warung.monitor.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes the single [DeviceSettingsEntity] row.
 *
 * [save] replaces the row wholesale: a partially updated tuning would describe
 * a session that never ran. There is no history here on purpose, because the
 * outbox and `table_logs` are the tables that must never lose data.
 */
@Dao
interface DeviceSettingsDao {

    @Query("SELECT * FROM device_settings WHERE id = ${DeviceSettingsEntity.SINGLETON_ID}")
    suspend fun settings(): DeviceSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: DeviceSettingsEntity)

    @Query("DELETE FROM device_settings")
    suspend fun clear()
}
