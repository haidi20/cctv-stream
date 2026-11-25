package id.warung.monitor.feature.tables.data

import id.warung.monitor.core.model.MappingFrame
import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.TableConfig
import id.warung.monitor.core.model.TableConfigSet
import id.warung.monitor.core.model.TableId
import id.warung.monitor.core.model.TableLabel
import id.warung.monitor.core.model.TablePolygon
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryTableConfigRepositoryTest {

    private fun config(id: String = "meja-01"): TableConfigSet = TableConfigSet(
        frame = MappingFrame(1280, 720, "16:9"),
        tables = listOf(
            TableConfig(
                TableId(id),
                TableLabel("Meja"),
                TablePolygon.orNull(
                    listOf(
                        NormalizedPoint(0.1, 0.1),
                        NormalizedPoint(0.3, 0.1),
                        NormalizedPoint(0.3, 0.3),
                    ),
                )!!,
            ),
        ),
    )

    @Test
    fun active_is_null_before_the_first_save() = runTest {
        val repository = InMemoryTableConfigRepository()
        assertNull(repository.active("cam"))
    }

    @Test
    fun revisions_increment_per_camera() = runTest {
        val repository = InMemoryTableConfigRepository()
        assertEquals(1, repository.save("cam-a", config()).revision)
        assertEquals(2, repository.save("cam-a", config()).revision)
        assertEquals(1, repository.save("cam-b", config()).revision)
    }

    @Test
    fun only_twenty_revisions_are_retained() = runTest {
        val repository = InMemoryTableConfigRepository()
        repeat(25) { repository.save("cam", config()) }
        assertEquals(20, repository.revisionCount("cam"))
        assertEquals(25L, repository.active("cam")?.revision)
    }

    @Test
    fun stored_config_is_quantised() = runTest {
        val raw = TableConfigSet(
            frame = MappingFrame(1280, 720, "16:9"),
            tables = listOf(
                TableConfig(
                    TableId("meja-01"),
                    TableLabel("Meja"),
                    TablePolygon.orNull(
                        listOf(
                            NormalizedPoint(0.123456, 0.1),
                            NormalizedPoint(0.333333, 0.1),
                            NormalizedPoint(0.333333, 0.3),
                        ),
                    )!!,
                ),
            ),
        )
        val stored = InMemoryTableConfigRepository().save("cam", raw)
        assertEquals(0.1235, stored.config.tables.single().polygon.points.first().x, 0.0)
    }
}