package id.warung.monitor.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves version 1 maps survive the upgrade.
 *
 * `MigrationTestHelper` cannot open its file through Robolectric's SQLite path
 * mapping in this setup, so the version-1 file is written directly with the
 * exported schema and the identity hash from `schemas/.../1.json`. Room then
 * runs the real [MonitorDatabase.MIGRATION_1_2] when the database is opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MonitorDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun version_one_revisions_survive_and_are_owned_by_the_seeded_camera() {
        createVersionOneDatabase()

        val database = database()
        try {
            runBlocking {
                val revision = database.tableConfigRevisions().active(CameraProfileEntity.DEFAULT_ID)
                assertNotNull(revision)
                assertEquals(7L, revision?.revision)
                assertEquals(42L, revision?.createdAt)

                val seeded = database.cameraProfiles().find(CameraProfileEntity.DEFAULT_ID)
                assertNotNull(seeded)
                assertEquals("Kamera Utama", seeded?.label)
                assertEquals(CameraProfileEntity.SOURCE_DEMO, seeded?.sourceType)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migration_is_idempotent_when_the_seeded_camera_already_exists() {
        createVersionOneDatabase()
        val raw = SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DB).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            raw.execSQL(
                """
                CREATE TABLE `camera_profiles` (
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
            raw.execSQL(
                "INSERT INTO camera_profiles VALUES " +
                    "('camera-default', 'Sudah Ada', 'rtsp', '192.168.1.64', 554, " +
                    "'/stream', 'viewer', 1, 1, NULL)",
            )
        } finally {
            raw.close()
        }

        val database = database()
        try {
            runBlocking {
                assertEquals(1, database.cameraProfiles().count())
                assertEquals("Sudah Ada", database.cameraProfiles().find("camera-default")?.label)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun version_two_profiles_and_maps_survive_the_outbox_migration() {
        createVersionTwoDatabase()

        val database = database()
        try {
            runBlocking {
                val profile = database.cameraProfiles().find("cam-dapur")
                assertNotNull(profile)
                assertEquals("Dapur", profile?.label)

                val revision = database.tableConfigRevisions().active("cam-dapur")
                assertNotNull(revision)
                assertEquals(3L, revision?.revision)

                // The new table exists and starts empty for an upgraded install.
                assertEquals(0, database.outbox().pendingCount())
            }
        } finally {
            database.close()
        }
    }

    private fun database(): MonitorDatabase =
        Room.databaseBuilder(context, MonitorDatabase::class.java, TEST_DB)
            .addMigrations(MonitorDatabase.MIGRATION_1_2, MonitorDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

    /** Writes the version-1 schema exactly as Room exported it. */
    private fun createVersionOneDatabase() {
        val path = context.getDatabasePath(TEST_DB)
        path.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            raw.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `table_config_revisions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `camera_id` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `config_json` TEXT NOT NULL,
                    `frame_width` INTEGER NOT NULL,
                    `frame_height` INTEGER NOT NULL,
                    `aspect_ratio` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            raw.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_table_config_revisions_camera_id_revision` " +
                    "ON `table_config_revisions` (`camera_id`, `revision`)",
            )
            raw.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_table_config_revisions_camera_id_created_at` " +
                    "ON `table_config_revisions` (`camera_id`, `created_at`)",
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            raw.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES " +
                    "(42, '$VERSION_ONE_IDENTITY')",
            )
            raw.execSQL(
                """
                INSERT INTO table_config_revisions
                    (camera_id, revision, config_json, frame_width, frame_height,
                     aspect_ratio, created_at)
                VALUES ('camera-default', 7, '{"version":2}', 1280, 720, '16:9', 42)
                """.trimIndent(),
            )
            raw.version = 1
        } finally {
            raw.close()
        }
    }

    /** Writes the version-2 schema exactly as Room exported it, with real rows. */
    private fun createVersionTwoDatabase() {
        val path = context.getDatabasePath(TEST_DB)
        path.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            raw.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `table_config_revisions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `camera_id` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `config_json` TEXT NOT NULL,
                    `frame_width` INTEGER NOT NULL,
                    `frame_height` INTEGER NOT NULL,
                    `aspect_ratio` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            raw.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_table_config_revisions_camera_id_revision` " +
                    "ON `table_config_revisions` (`camera_id`, `revision`)",
            )
            raw.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_table_config_revisions_camera_id_created_at` " +
                    "ON `table_config_revisions` (`camera_id`, `created_at`)",
            )
            raw.execSQL(
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
            raw.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_camera_profiles_label` " +
                    "ON `camera_profiles` (`label`)",
            )
            raw.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            raw.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES " +
                    "(42, '$VERSION_TWO_IDENTITY')",
            )
            raw.execSQL(
                "INSERT INTO camera_profiles VALUES " +
                    "('cam-dapur', 'Dapur', 'rtsp', '10.0.0.5', 554, '/live', " +
                    "'viewer', 10, 20, NULL)",
            )
            raw.execSQL(
                "INSERT INTO table_config_revisions " +
                    "(camera_id, revision, config_json, frame_width, frame_height, " +
                    "aspect_ratio, created_at) " +
                    "VALUES ('cam-dapur', 3, '{\"version\":2}', 1280, 720, '16:9', 30)",
            )
            raw.version = 2
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"

        /** `identityHash` from `schemas/.../1.json`. */
        const val VERSION_ONE_IDENTITY = "2b49c56b286715fc6c3fc6e3047154ab"

        /** `identityHash` from `schemas/.../2.json`. */
        const val VERSION_TWO_IDENTITY = "3790572467de5967a2e2613443736c9a"
    }
}
