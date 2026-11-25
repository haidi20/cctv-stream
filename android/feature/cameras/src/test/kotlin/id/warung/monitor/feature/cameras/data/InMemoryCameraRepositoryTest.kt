package id.warung.monitor.feature.cameras.data

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryCameraRepositoryTest {

    private fun profile(id: String, label: String) = CameraProfile(
        id = CameraId(id),
        label = CameraLabel(label),
        source = CameraSource.Demo,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun profiles_are_sorted_by_label_then_id() = runTest {
        val repository = InMemoryCameraRepository()
        repository.upsert(profile("cam-b", "Warung Belakang"))
        repository.upsert(profile("cam-a", "Warung Depan"))
        repository.upsert(profile("cam-c", "Gudang"))

        assertEquals(listOf("cam-c", "cam-b", "cam-a"), repository.all().map { it.id.value })
    }

    @Test
    fun updating_a_label_keeps_the_same_id() = runTest {
        val repository = InMemoryCameraRepository(listOf(profile("cam-a", "Lama")))
        repository.upsert(profile("cam-a", "Baru"))

        assertEquals("Baru", repository.find(CameraId("cam-a"))?.label?.value)
        assertEquals(1, repository.size())
    }

    @Test
    fun marking_opened_records_the_timestamp() = runTest {
        val repository = InMemoryCameraRepository(listOf(profile("cam-a", "Depan")))
        repository.markOpened(CameraId("cam-a"), 99L)

        assertEquals(99L, repository.find(CameraId("cam-a"))?.lastOpenedAt)
    }

    @Test
    fun deleting_an_unknown_camera_is_a_no_op() = runTest {
        val repository = InMemoryCameraRepository(listOf(profile("cam-a", "Depan")))
        repository.delete(CameraId("cam-b"))

        assertEquals(1, repository.size())
        assertNull(repository.find(CameraId("cam-b")))
    }
}