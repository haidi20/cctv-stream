package id.warung.monitor.engine

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.feature.cameras.data.InMemoryCameraRepository
import id.warung.monitor.feature.tables.data.InMemoryTableConfigRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The seed only runs in a debug build (`BuildConfig.DEV_MODE`), which is the
 * variant the unit-test gate compiles, so these tests exercise the real branch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DevSeedTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val cameras = InMemoryCameraRepository()
    private val tables = InMemoryTableConfigRepository(clock = { 7L })

    private fun seed() = DevSeed(
        cameras = cameras,
        tables = tables,
        context = RuntimeEnvironment.getApplication(),
        ioDispatcher = dispatcher,
    )

    private fun videoProfile(label: String = "Rakitan Sendiri") = CameraProfile(
        id = DevSeed.DEV_CAMERA_ID,
        label = CameraLabel(label),
        source = CameraSource.Video(DevSeed.DEV_VIDEO_NAME),
        createdAt = 3L,
        updatedAt = 3L,
    )

    @Test
    fun a_pushed_dev_clip_is_copied_into_the_players_internal_video_dir() = runTest(dispatcher) {
        val context = RuntimeEnvironment.getApplication()
        val external = context.getExternalFilesDir(null)!!
        val videos = java.io.File(context.filesDir, "videos")
        java.io.File(external, DevSeed.DEV_VIDEO_NAME).writeText("clip")

        DevSeed(cameras, tables, context, dispatcher).ensureSeeded()

        assertTrue("clip must be where the player reads it", java.io.File(videos, DevSeed.DEV_VIDEO_NAME).isFile)
    }
    @Test
    fun a_debug_build_gets_one_local_video_camera_and_a_starter_map() = runTest(dispatcher) {
        assertTrue(seed().ensureSeeded())

        val camera = cameras.all().single()
        assertEquals(DevSeed.DEV_CAMERA_ID, camera.id)
        assertEquals(CameraSource.Video(DevSeed.DEV_VIDEO_NAME), camera.source)
        assertEquals("CCTV 1", camera.label.value)

        val stored = tables.active(DevSeed.DEV_CAMERA_ID.value)
        assertNotNull("dev mode needs a map so Rust can report tables", stored)
        assertEquals(
            DevSeed.starterConfig().tables.map { it.id },
            stored!!.config.tables.map { it.id },
        )
    }

    @Test
    fun the_legacy_four_table_dev_map_is_replaced_by_six_tables() = runTest(dispatcher) {
        val legacy = id.warung.monitor.core.model.TableConfigSet(
            frame = DevSeed.starterConfig().frame,
            tables = DevSeed.starterConfig().tables.take(4),
        )
        tables.save(DevSeed.DEV_CAMERA_ID.value, legacy)

        seed().ensureSeeded()

        assertEquals(6, tables.active(DevSeed.DEV_CAMERA_ID.value)!!.config.tables.size)
    }

    @Test
    fun seeding_twice_leaves_one_camera_and_one_revision() = runTest(dispatcher) {
        val seed = seed()
        seed.ensureSeeded()
        seed.ensureSeeded()

        assertEquals(1, cameras.all().size)
        assertEquals(1, tables.revisionCount(DevSeed.DEV_CAMERA_ID.value))
    }

    @Test
    fun an_old_dev_clip_source_is_migrated_to_the_current_clip() = runTest(dispatcher) {
        cameras.upsert(
            videoProfile().copy(source = CameraSource.Video("istockphoto-1067633772-640_adpp_is.mp4")),
        )

        seed().ensureSeeded()

        val source = cameras.all().single().source
        assertTrue(source is CameraSource.Video)
        assertEquals(DevSeed.DEV_VIDEO_NAME, (source as CameraSource.Video).fileName)
    }

    @Test
    fun a_user_renamed_camera_and_edited_map_are_never_overwritten() = runTest(dispatcher) {
        cameras.upsert(videoProfile())
        val edited = DevSeed.starterConfig().copy(
            tables = DevSeed.starterConfig().tables.take(1),
        )
        tables.save(DevSeed.DEV_CAMERA_ID.value, edited)

        assertTrue(seed().ensureSeeded())

        assertEquals("Rakitan Sendiri", cameras.all().single().label.value)
        assertEquals(1, tables.revisionCount(DevSeed.DEV_CAMERA_ID.value))
        assertEquals(
            1,
            tables.active(DevSeed.DEV_CAMERA_ID.value)!!.config.tables.size,
        )
    }

    @Test
    fun the_seeded_camera_id_survives_a_restart() = runTest(dispatcher) {
        seed().ensureSeeded()
        val id = cameras.all().single().id

        // A second app start (new DevSeed instance) must reuse the same profile.
        assertTrue(seed().ensureSeeded())

        assertEquals(id, cameras.all().single().id)
        assertEquals(CameraId("cctv-simulasi"), id)
    }
}
