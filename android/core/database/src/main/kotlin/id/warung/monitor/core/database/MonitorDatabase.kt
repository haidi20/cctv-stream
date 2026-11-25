package id.warung.monitor.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's single Room database.
 *
 * Any later schema change must bump [VERSION] and add a [Migration];
 * `fallbackToDestructiveMigration` is deliberately not used because a table map
 * is the only copy of a user's calibration. Version 2 adds camera profiles and
 * keeps every revision written by version 1 under `camera-default`.
 */
@Database(
    entities = [TableConfigRevisionEntity::class, CameraProfileEntity::class],
    version = MonitorDatabase.VERSION,
    exportSchema = true,
)
abstract class MonitorDatabase : RoomDatabase() {

    abstract fun tableConfigRevisions(): TableConfigRevisionDao

    abstract fun cameraProfiles(): CameraProfileDao

    companion object {
        const val VERSION: Int = 2
        const val NAME: String = "monitor.db"

        /** Seeds the MVP camera so version-1 maps stay reachable after upgrade. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `camera_profiles` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `source_type` TEXT NOT NULL,
                        `host` TEXT,
                        `port` INTEGER,
                        `stream_path` TEXT,
                        `username` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `last_opened_at` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_camera_profiles_label` " +
                        "ON `camera_profiles` (`label`)",
                )
                database.execSQL(
                    """
                    INSERT OR IGNORE INTO `camera_profiles`
                        (`id`, `label`, `source_type`, `host`, `port`, `stream_path`,
                         `username`, `created_at`, `updated_at`, `last_opened_at`)
                    VALUES
                        ('camera-default', 'Kamera Utama', 'demo', NULL, NULL, NULL,
                         NULL, 0, 0, NULL)
                    """.trimIndent(),
                )
            }
        }

        fun create(context: Context): MonitorDatabase =
            Room.databaseBuilder(context, MonitorDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}