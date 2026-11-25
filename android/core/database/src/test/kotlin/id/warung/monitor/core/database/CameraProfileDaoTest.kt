package id.warung.monitor.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class CameraProfileDaoTest {

    private lateinit var database: MonitorDatabase
    private lateinit var dao: CameraProfileDao
    private lateinit var revisions: TableConfigRevisionDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MonitorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.cameraProfiles()
        revisions = database.tableConfigRevisions()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun profile(
        id: String,
        label: String = "Kamera $id",
        sourceType: String = CameraProfileEntity.SOURCE_DEMO,
        lastOpenedAt: Long? = null,
    ) = CameraProfileEntity(
        id = id,
        label = label,
        sourceType = sourceType,
        host = null,
        port = null,
        streamPath = null,
        username = null,
        createdAt = 1L,
        updatedAt = 1L,
        lastOpenedAt = lastOpenedAt,
    )

    @Test
    fun cameras_are_listed_by_label_case_insensitively() = runTest {
        dao.upsert(profile("cam-b", label = "warung belakang"))
        dao.upsert(profile("cam-a", label = "Warung Depan"))
        dao.upsert(profile("cam-c", label = "Gudang"))

        assertEquals(listOf("cam-c", "cam-b", "cam-a"), dao.all().map { it.id })
        assertEquals(listOf("cam-c", "cam-b", "cam-a"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun upsert_updates_an_existing_camera_without_duplicating_it() = runTest {
        dao.upsert(profile("cam-a", label = "Lama"))
        dao.upsert(profile("cam-a", label = "Baru"))

        assertEquals(1, dao.count())
        assertEquals("Baru", dao.find("cam-a")?.label)
    }

    @Test
    fun marking_a_camera_opened_records_the_timestamp() = runTest {
        dao.upsert(profile("cam-a"))

        dao.markOpened("cam-a", 1234L)

        assertEquals(1234L, dao.find("cam-a")?.lastOpenedAt)
    }

    @Test
    fun deleting_a_camera_removes_its_revisions_but_keeps_other_cameras() = runTest {
        dao.upsert(profile("cam-a"))
        dao.upsert(profile("cam-b"))
        revisions.insertNextRevision("cam-a", "{}", 1280, 720, "16:9", 1L, 20)
        revisions.insertNextRevision("cam-b", "{}", 1280, 720, "16:9", 2L, 20)

        dao.deleteWithRevisions("cam-a")

        assertNull(dao.find("cam-a"))
        assertEquals(0, revisions.count("cam-a"))
        assertEquals(1, revisions.count("cam-b"))
        assertEquals(1, dao.count())
    }
}