package id.warung.monitor.feature.cameras.data

import id.warung.monitor.core.database.CameraProfileDao
import id.warung.monitor.core.database.CameraProfileEntity
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Offline-first camera profiles. Room is the source of truth. */
interface CameraRepository {

    fun observeAll(): Flow<List<CameraProfile>>

    suspend fun all(): List<CameraProfile>

    suspend fun find(id: CameraId): CameraProfile?

    suspend fun upsert(profile: CameraProfile)

    suspend fun markOpened(id: CameraId, at: Long)

    /** Removes the profile and every table revision it owns. */
    suspend fun delete(id: CameraId)

    /**
     * Turns one camera's monitoring on or off.
     *
     * Nothing else about the profile changes, so resuming never needs a
     * re-scan or a fresh table map.
     */
    suspend fun setMonitoringEnabled(id: CameraId, enabled: Boolean)
}

class RoomCameraRepository(
    private val dao: CameraProfileDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : CameraRepository {

    override fun observeAll(): Flow<List<CameraProfile>> =
        dao.observeAll().map { rows -> rows.mapNotNull(CameraProfileEntity::toProfile) }

    override suspend fun all(): List<CameraProfile> = withContext(ioDispatcher) {
        dao.all().mapNotNull(CameraProfileEntity::toProfile)
    }

    override suspend fun find(id: CameraId): CameraProfile? = withContext(ioDispatcher) {
        dao.find(id.value)?.toProfile()
    }

    override suspend fun upsert(profile: CameraProfile): Unit = withContext(ioDispatcher) {
        val now = clock()
        val existing = dao.find(profile.id.value)
        dao.upsert(
            CameraProfileEntity(
                id = profile.id.value,
                label = profile.label.value,
                sourceType = profile.source.cameraSourceType(),
                host = (profile.source as? CameraSource.Rtsp)?.host,
                port = (profile.source as? CameraSource.Rtsp)?.port,
                streamPath = when (val source = profile.source) {
                    is CameraSource.Rtsp -> source.path
                    is CameraSource.Video -> source.fileName
                    CameraSource.Demo -> null
                },
                username = (profile.source as? CameraSource.Rtsp)?.username,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lastOpenedAt = profile.lastOpenedAt ?: existing?.lastOpenedAt,
                monitoringEnabled = profile.monitoringEnabled,
            ),
        )
    }

    override suspend fun markOpened(id: CameraId, at: Long): Unit = withContext(ioDispatcher) {
        dao.markOpened(id.value, at)
    }

    override suspend fun delete(id: CameraId): Unit = withContext(ioDispatcher) {
        dao.deleteWithRevisions(id.value)
    }

    override suspend fun setMonitoringEnabled(id: CameraId, enabled: Boolean): Unit =
        withContext(ioDispatcher) {
            dao.setMonitoringEnabled(id.value, enabled)
        }

    companion object {
        fun from(dao: CameraProfileDao): RoomCameraRepository = RoomCameraRepository(dao)
    }
}

internal fun CameraSource.cameraSourceType(): String = when (this) {
    CameraSource.Demo -> CameraProfileEntity.SOURCE_DEMO
    is CameraSource.Video -> CameraProfileEntity.SOURCE_VIDEO
    is CameraSource.Rtsp -> CameraProfileEntity.SOURCE_RTSP
}

/**
 * Decodes a stored row, or `null` when it cannot represent a valid profile.
 *
 * A corrupt row is skipped rather than crashing the list; the id stays in Room
 * so a later migration can repair it instead of silently dropping user data.
 */
internal fun CameraProfileEntity.toProfile(): CameraProfile? {
    val cameraId = runCatching { CameraId(id) }.getOrNull() ?: return null
    val cameraLabel = runCatching { CameraLabel(label) }.getOrNull() ?: return null
    val cameraSource = when (sourceType) {
        CameraProfileEntity.SOURCE_DEMO -> CameraSource.Demo
        CameraProfileEntity.SOURCE_VIDEO -> {
            val file = streamPath ?: return null
            runCatching { CameraSource.Video(file) }.getOrNull() ?: return null
        }
        CameraProfileEntity.SOURCE_RTSP -> {
            val host = host ?: return null
            val streamPort = port ?: return null
            val streamPath = streamPath ?: return null
            runCatching {
                CameraSource.Rtsp(host = host, port = streamPort, path = streamPath, username = username)
            }.getOrNull() ?: return null
        }
        else -> return null
    }
    return CameraProfile(
        id = cameraId,
        label = cameraLabel,
        source = cameraSource,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastOpenedAt = lastOpenedAt,
        monitoringEnabled = monitoringEnabled,
    )
}