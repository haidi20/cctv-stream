package id.warung.monitor.data

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.MappingFrame
import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.TableConfig
import id.warung.monitor.core.model.TableConfigSet
import id.warung.monitor.core.model.TableId
import id.warung.monitor.core.model.TableLabel
import id.warung.monitor.core.model.TablePolygon
import id.warung.monitor.feature.home.domain.SavedTableMap
import id.warung.monitor.feature.tables.data.InMemoryTableConfigRepository
import id.warung.monitor.feature.tables.data.TableConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomTableMappingSourceTest {

    private fun table(id: String, label: String): TableConfig = TableConfig(
        id = TableId(id),
        label = TableLabel(label),
        polygon = requireNotNull(
            TablePolygon.orNull(
                listOf(
                    NormalizedPoint(0.1, 0.1),
                    NormalizedPoint(0.3, 0.1),
                    NormalizedPoint(0.3, 0.3),
                    NormalizedPoint(0.1, 0.3),
                ),
            ),
        ),
    )

    private fun config(vararg tables: TableConfig): TableConfigSet = TableConfigSet(
        frame = MappingFrame(1280, 720, "16:9"),
        tables = tables.toList(),
    )

    @Test
    fun a_camera_with_nothing_saved_reports_no_map() = runTest {
        val source = RoomTableMappingSource(InMemoryTableConfigRepository())

        assertNull(source.observe(CameraId("cam-1")).first())
    }

    private val square = listOf(
        NormalizedPoint(0.1, 0.1),
        NormalizedPoint(0.3, 0.1),
        NormalizedPoint(0.3, 0.3),
        NormalizedPoint(0.1, 0.3),
    )

    @Test
    fun the_saved_revision_polygon_count_and_footprints_are_reported() = runTest {
        val repository = InMemoryTableConfigRepository()
        repository.save("cam-1", config(table("meja-01", "Meja 1"), table("meja-02", "Meja 2")))
        val source = RoomTableMappingSource(repository)

        assertEquals(
            SavedTableMap(tables = 2, revision = 1, polygons = listOf(square, square)),
            source.observe(CameraId("cam-1")).first(),
        )
    }

    @Test
    fun a_new_revision_reaches_an_open_collector() = runTest {
        val repository = InMemoryTableConfigRepository()
        repository.save("cam-1", config(table("meja-01", "Meja 1")))
        val source = RoomTableMappingSource(repository)

        assertEquals(
            SavedTableMap(tables = 1, revision = 1, polygons = listOf(square)),
            source.observe(CameraId("cam-1")).first(),
        )

        repository.save("cam-1", config(table("meja-01", "Meja 1"), table("meja-02", "Meja 2")))

        assertEquals(
            SavedTableMap(tables = 2, revision = 2, polygons = listOf(square, square)),
            source.observe(CameraId("cam-1")).first(),
        )
    }

    @Test
    fun a_broken_store_reports_no_map_so_the_user_can_redraw_it() = runTest {
        val broken = object : TableConfigRepository {
            override suspend fun active(cameraId: String) = null
            override fun observeActive(cameraId: String): Flow<id.warung.monitor.feature.tables.data.StoredTableConfig?> =
                flow { throw IllegalStateException("row rusak") }
            override suspend fun save(
                cameraId: String,
                config: TableConfigSet,
            ) = throw UnsupportedOperationException("read-only fake")
        }
        val source = RoomTableMappingSource(broken)

        assertNull(source.observe(CameraId("cam-1")).first())
    }
}
