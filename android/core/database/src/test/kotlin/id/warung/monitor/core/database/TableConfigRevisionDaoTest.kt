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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TableConfigRevisionDaoTest {

    private lateinit var database: MonitorDatabase
    private lateinit var dao: TableConfigRevisionDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MonitorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.tableConfigRevisions()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun active_is_null_before_the_first_revision() = runTest {
        assertNull(dao.active("cam"))
    }

    @Test
    fun revisions_start_at_one_and_increment() = runTest {
        val first = dao.insertNextRevision("cam", "{}", 1280, 720, "16:9", 1L, 20)
        val second = dao.insertNextRevision("cam", "{}", 1280, 720, "16:9", 2L, 20)
        assertEquals(1L, first.revision)
        assertEquals(2L, second.revision)
        assertEquals(2L, dao.active("cam")?.revision)
    }

    @Test
    fun revisions_are_scoped_per_camera() = runTest {
        dao.insertNextRevision("cam-a", "{}", 1280, 720, "16:9", 1L, 20)
        dao.insertNextRevision("cam-a", "{}", 1280, 720, "16:9", 2L, 20)
        val firstB = dao.insertNextRevision("cam-b", "{}", 640, 480, "4:3", 3L, 20)
        assertEquals(1L, firstB.revision)
        assertEquals(2L, dao.active("cam-a")?.revision)
    }

    @Test
    fun history_is_pruned_to_twenty_revisions() = runTest {
        repeat(25) { index ->
            dao.insertNextRevision("cam", "{\"n\":$index}", 1280, 720, "16:9", index.toLong(), 20)
        }
        assertEquals(20, dao.count("cam"))
        val rows = dao.history("cam")
        assertEquals(6L, rows.first().revision)
        assertEquals(25L, rows.last().revision)
        assertEquals("{\"n\":24}", dao.active("cam")?.configJson)
    }

    @Test
    fun frame_columns_match_the_inserted_values() = runTest {
        dao.insertNextRevision("cam", "{}", 1920, 1080, "16:9", 42L, 20)
        val row = dao.active("cam")!!
        assertEquals(1920, row.frameWidth)
        assertEquals(1080, row.frameHeight)
        assertEquals("16:9", row.aspectRatio)
        assertEquals(42L, row.createdAt)
    }
}