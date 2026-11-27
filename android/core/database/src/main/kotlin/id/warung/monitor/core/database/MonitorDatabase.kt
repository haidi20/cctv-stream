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
 * keeps every revision written by version 1 under `camera-default`. Version 3
 * adds the sync outbox; it is additive and never rewrites existing rows.
 * Version 4 adds the per-camera `monitoring_enabled` switch; it preserves
 * every profile and map and defaults existing cameras to on.
 * Version 5 adds a nullable `device_id` so a camera can keep its ONVIF UUID
 * when DHCP changes its IP. Existing profiles stay null and keep working.
 */
@Database(
    entities = [
        TableConfigRevisionEntity::class,
        CameraProfileEntity::class,
        OutboxEntity::class,
    ],
    version = MonitorDatabase.VERSION,
    exportSchema = true,
)
abstract class MonitorDatabase : RoomDatabase() {

    abstract fun tableConfigRevisions(): TableConfigRevisionDao

    abstract fun cameraProfiles(): CameraProfileDao

    abstract fun outbox(): OutboxDao

    companion object {
        const val VERSION: Int = 5
        const val NAME: String = "monitor.db"

        /** Seeds the MVP camera so version-1 maps stay reachable after upgrade. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_camera_profiles_label` " +
                        "ON `camera_profiles` (`label`)",
                )
                db.execSQL(
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

        /** Adds the sync queue without touching camera profiles or table maps. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `outbox` (
                        `seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `device_id` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `last_error` TEXT,
                        `is_synced` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_outbox_pending` " +
                        "ON `outbox` (`is_synced`, `seq`)",
                )
            }
        }

        /**
         * Adds `monitoring_enabled` without touching existing data.
         *
         * `DEFAULT 1` backfills every saved camera as on, so an upgrade never
         * silently stops monitoring a warung that was already running.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `camera_profiles` ADD COLUMN " +
                        "`monitoring_enabled` INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        /** Adds the stable ONVIF identity without touching any existing row. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `camera_profiles` ADD COLUMN `device_id` TEXT",
                )
            }
        }

        fun create(context: Context): MonitorDatabase =
            Room.databaseBuilder(context, MonitorDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
