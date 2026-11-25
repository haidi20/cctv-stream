package id.warung.monitor.feature.tables.data

import id.warung.monitor.core.database.MonitorDatabase
import id.warung.monitor.core.database.TableConfigRevisionDao
import id.warung.monitor.core.database.TableConfigRevisionRow
import id.warung.monitor.core.model.TableConfigDecodeResult
import id.warung.monitor.core.model.TableConfigJson
import id.warung.monitor.core.model.TableConfigSet
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed repository.
 *
 * The JSON column is the source of truth; coordinates and the frame are decoded
 * from it so a corrupt row can never desynchronise from the diagnostics columns.
 */
class RoomTableConfigRepository(
    private val dao: TableConfigRevisionDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : TableConfigRepository {

    override suspend fun active(cameraId: String): StoredTableConfig? = withContext(ioDispatcher) {
        dao.active(cameraId)?.toStored()
    }

    override fun observeActive(cameraId: String): Flow<StoredTableConfig?> =
        dao.observeActive(cameraId).map { it?.toStored() }

    override suspend fun save(cameraId: String, config: TableConfigSet): StoredTableConfig =
        withContext(ioDispatcher) {
            val json = TableConfigJson.encodeOrThrow(config)
            val row = dao.insertNextRevision(
                cameraId = cameraId,
                configJson = json,
                frameWidth = config.frame.width,
                frameHeight = config.frame.height,
                aspectRatio = config.frame.aspectRatio,
                createdAt = clock(),
                keep = TableConfigRepository.MAX_REVISIONS,
            )
            StoredTableConfig(
                cameraId = row.cameraId,
                revision = row.revision,
                config = config.quantized(),
                createdAt = row.createdAt,
            )
        }

    private fun TableConfigRevisionRow.toStored(): StoredTableConfig? =
        when (val decoded = TableConfigJson.decode(configJson)) {
            is TableConfigDecodeResult.Success -> StoredTableConfig(
                cameraId = cameraId,
                revision = revision,
                config = decoded.config,
                createdAt = createdAt,
            )
            is TableConfigDecodeResult.Failure -> throw IOException(
                "Revisi ${revision.toString()} kamera $cameraId rusak: ${decoded.message}",
            )
        }

    companion object {
        fun from(database: MonitorDatabase): RoomTableConfigRepository =
            RoomTableConfigRepository(database.tableConfigRevisions())
    }
}