package id.warung.monitor.feature.tables.data

import id.warung.monitor.core.model.TableConfigSet
import kotlinx.coroutines.flow.Flow

/** A saved map plus the revision number it was stored under. */
data class StoredTableConfig(
    val cameraId: String,
    val revision: Long,
    val config: TableConfigSet,
    val createdAt: Long,
)

/** Offline-first storage for camera table maps. Room is the source of truth. */
interface TableConfigRepository {

    suspend fun active(cameraId: String): StoredTableConfig?

    fun observeActive(cameraId: String): Flow<StoredTableConfig?>

    /** Stores a new revision and returns it; the caller may then notify the engine. */
    suspend fun save(cameraId: String, config: TableConfigSet): StoredTableConfig

    companion object {
        /** Revisions retained per camera; older maps are pruned after insert. */
        const val MAX_REVISIONS: Int = 20
    }
}