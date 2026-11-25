package id.warung.monitor.feature.tables.data

import id.warung.monitor.core.model.TableConfigSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic repository for JVM tests and Compose previews.
 *
 * Revision numbers and pruning follow [TableConfigRepository] exactly so a test
 * of the ViewModel also covers the contract Room implements.
 */
class InMemoryTableConfigRepository(
    private val clock: () -> Long = System::currentTimeMillis,
    private val keepRevisions: Int = TableConfigRepository.MAX_REVISIONS,
) : TableConfigRepository {

    private data class Entry(
        val revision: Long,
        val config: TableConfigSet,
        val createdAt: Long,
    )

    private val revisions = LinkedHashMap<String, MutableList<Entry>>()
    private val activeFlows = HashMap<String, MutableStateFlow<StoredTableConfig?>>()

    override suspend fun active(cameraId: String): StoredTableConfig? =
        revisions[cameraId]?.maxByOrNull { it.revision }?.toStored(cameraId)

    override fun observeActive(cameraId: String): Flow<StoredTableConfig?> =
        activeFlows.getOrPut(cameraId) { MutableStateFlow(activeNow(cameraId)) }.asStateFlow()

    override suspend fun save(cameraId: String, config: TableConfigSet): StoredTableConfig {
        val list = revisions.getOrPut(cameraId) { mutableListOf() }
        val revision = (list.maxOfOrNull { it.revision } ?: 0L) + 1
        val entry = Entry(revision, config.quantized(), clock())
        list += entry
        if (list.size > keepRevisions) {
            list.sortBy { it.revision }
            while (list.size > keepRevisions) list.removeAt(0)
        }
        val stored = entry.toStored(cameraId)
        activeFlows.getOrPut(cameraId) { MutableStateFlow(activeNow(cameraId)) }.value = stored
        return stored
    }

    /** Test helper: every stored revision for a camera, oldest first. */
    fun revisionCount(cameraId: String): Int = revisions[cameraId]?.size ?: 0

    private fun activeNow(cameraId: String): StoredTableConfig? =
        revisions[cameraId]?.maxByOrNull { it.revision }?.toStored(cameraId)

    private fun Entry.toStored(cameraId: String): StoredTableConfig = StoredTableConfig(
        cameraId = cameraId,
        revision = revision,
        config = config,
        createdAt = createdAt,
    )
}