package id.warung.monitor.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `device_settings` holds exactly one row, and a save replaces it.
 *
 * The failure this guards against is subtle: an upsert that appends instead of
 * replacing would leave a stale row that the planner reads first, so the phone
 * would never pick up a new measurement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceSettingsDaoTest {

    private lateinit var database: MonitorDatabase
    private lateinit var dao: DeviceSettingsDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MonitorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.deviceSettings()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun row(
        fps: Double = 2.0,
        threads: Int = 2,
        p95: Long? = 296L,
        updatedAt: Long = 10L,
    ) = DeviceSettingsEntity(
        inferenceFps = fps,
        intraThreads = threads,
        p95InferenceMs = p95,
        updatedAt = updatedAt,
    )

    @Test
    fun an_empty_table_reads_as_no_measurement() = runTest {
        assertNull(dao.settings())
    }

    @Test
    fun a_saved_row_is_read_back_unchanged() = runTest {
        dao.save(row())

        val stored = dao.settings()
        assertEquals(2.0, stored?.inferenceFps ?: 0.0, 0.0)
        assertEquals(2, stored?.intraThreads)
        assertEquals(296L, stored?.p95InferenceMs)
        assertEquals(10L, stored?.updatedAt)
    }

    @Test
    fun saving_twice_replaces_the_row_instead_of_appending() = runTest {
        dao.save(row(fps = 2.0, p95 = 296L, updatedAt = 10L))
        dao.save(row(fps = 1.0, threads = 1, p95 = 640L, updatedAt = 20L))

        val stored = dao.settings()
        assertEquals(1.0, stored?.inferenceFps ?: 0.0, 0.0)
        assertEquals(1, stored?.intraThreads)
        assertEquals(640L, stored?.p95InferenceMs)
        assertEquals("the newer measurement must win", 20L, stored?.updatedAt)
    }

    @Test
    fun a_missing_latency_is_stored_as_null_not_zero() = runTest {
        dao.save(row(p95 = null))

        assertNull("null and zero mean different things", dao.settings()?.p95InferenceMs)
    }

    @Test
    fun clearing_leaves_the_table_empty_again() = runTest {
        dao.save(row())

        dao.clear()

        assertNull(dao.settings())
    }
}
