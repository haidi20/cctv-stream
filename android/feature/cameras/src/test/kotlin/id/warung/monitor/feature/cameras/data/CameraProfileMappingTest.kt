package id.warung.monitor.feature.cameras.data

import id.warung.monitor.core.database.CameraProfileEntity
import id.warung.monitor.core.model.CameraSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Room has no column for a video path, so a dev-mode camera reuses `stream_path`.
 * The mapping must survive the round trip and reject a row it cannot rebuild.
 */
class CameraProfileMappingTest {

    private fun videoRow(streamPath: String? = "clip.mp4") = CameraProfileEntity(
        id = "cctv-simulasi",
        label = "Simulasi CCTV",
        sourceType = CameraProfileEntity.SOURCE_VIDEO,
        host = null,
        port = null,
        streamPath = streamPath,
        username = null,
        createdAt = 1L,
        updatedAt = 1L,
        lastOpenedAt = null,
    )

    @Test
    fun a_video_camera_round_trips_through_the_room_columns() {
        val source = CameraSource.Video("clip.mp4")

        assertEquals(CameraProfileEntity.SOURCE_VIDEO, source.cameraSourceType())
        assertEquals(source, videoRow().toProfile()?.source)
    }

    @Test
    fun a_video_row_without_a_file_name_is_skipped_instead_of_crashing() {
        assertNull("a corrupt row must not break the camera list", videoRow(streamPath = null).toProfile())
    }

    @Test
    fun an_unknown_source_type_is_skipped() {
        assertNull(videoRow().copy(sourceType = "video-v2").toProfile())
    }
}