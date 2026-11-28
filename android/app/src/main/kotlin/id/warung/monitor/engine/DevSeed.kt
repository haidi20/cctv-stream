package id.warung.monitor.engine

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import id.warung.monitor.BuildConfig
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.model.MappingFrame
import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.TableConfig
import id.warung.monitor.core.model.TableConfigSet
import id.warung.monitor.core.model.TableId
import id.warung.monitor.core.model.TableLabel
import id.warung.monitor.core.model.TablePolygon
import id.warung.monitor.feature.cameras.data.CameraRepository
import id.warung.monitor.feature.tables.data.TableConfigRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seeds one local-video camera in debug builds.
 *
 * Dev mode must show the same preview + Rust analysis path a real camera uses,
 * without a network or a clip inside the APK. The video file is pushed to
 * `files/videos` by `tools/device-e2e.ps1`; this class only writes the profile
 * and a starter table map once, so a user edit is never overwritten.
 */
@Singleton
class DevSeed @Inject constructor(
    private val cameras: CameraRepository,
    private val tables: TableConfigRepository,
    @param:ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

        private fun copyPushedAssets() {
            val external = context.getExternalFilesDir(null) ?: return
            val videos = File(context.filesDir, "videos")
            val clip = File(external, DEV_VIDEO_NAME)
            if (clip.isFile) runCatching {
                videos.mkdirs()
                File(videos, DEV_VIDEO_NAME).apply { if (!isFile) clip.copyTo(this) }
            }.onFailure { error ->
                Log.w(TAG, "dev clip copy failed: " + error.javaClass.simpleName)
            }
        }
    /** Returns `true` when the dev camera exists after this call. */
    suspend fun ensureSeeded(): Boolean = withContext(ioDispatcher) {
        if (!BuildConfig.DEV_MODE) return@withContext false
        copyPushedAssets()
        val existing = runCatching { cameras.all().firstOrNull { it.id == DEV_CAMERA_ID } }.getOrNull()
        if (existing == null) {
            runCatching {
                cameras.upsert(
                    CameraProfile(
                        id = CameraId(DEV_CAMERA_ID.value),
                        label = CameraLabel(DEV_CAMERA_LABEL),
                        source = CameraSource.Video(fileName = DEV_VIDEO_NAME),
                        createdAt = 0L,
                        updatedAt = 0L,
                    ),
                )
            }.onFailure { error ->
                // No file name, path, or credential is ever logged.
                Log.w(TAG, "dev camera seed failed: " + error.javaClass.simpleName)
            }
        } else if (existing.source is CameraSource.Video &&
            (existing.source as CameraSource.Video).fileName != DEV_VIDEO_NAME
        ) {
            runCatching { cameras.upsert(existing.copy(source = CameraSource.Video(DEV_VIDEO_NAME))) }
                .onFailure { error ->
                    Log.w(TAG, "dev clip migration failed: " + error.javaClass.simpleName)
                }
        }
        val stored = runCatching { tables.active(DEV_CAMERA_ID.value) }.getOrNull()
        if (stored == null || stored.config.tables.isEmpty() || isLegacyStarterMap(stored)) {
            runCatching { tables.save(DEV_CAMERA_ID.value, starterConfig()) }
                .onFailure { error ->
                    Log.w(TAG, "dev table seed failed: " + error.javaClass.simpleName)
                }
        }
        runCatching { cameras.all().any { it.id == DEV_CAMERA_ID } }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "DevSeed"


        /** Stable id so a reinstall edits the same profile instead of adding one. */
        val DEV_CAMERA_ID: CameraId = CameraId("cctv-simulasi")

        const val DEV_CAMERA_LABEL: String = "CCTV 1"

        /** File name only; the player resolves it under the app's files dir. */
        const val DEV_VIDEO_NAME: String = "istockphoto-678973826-640_adpp_is.mp4"

        /**
         * Six 16:9 footprints over a 768x432 frame.
         *
         * They are deliberately rough: the point is to exercise assignment,
         * debounce, and the Rust overlay, not to match the clip's real tables.
         */
        fun starterConfig(): TableConfigSet = TableConfigSet(
            frame = MappingFrame(width = 768, height = 432, aspectRatio = "16:9"),
            tables = listOf(
                table("meja-01", "Meja 1", 0.04, 0.44, 0.24, 0.40),
                table("meja-02", "Meja 2", 0.30, 0.20, 0.24, 0.42),
                table("meja-03", "Meja 3", 0.58, 0.40, 0.26, 0.44),
                table("meja-04", "Meja 4", 0.36, 0.66, 0.24, 0.28),
                table("meja-05", "Meja 5", 0.04, 0.06, 0.24, 0.28),
                table("meja-06", "Meja 6", 0.70, 0.06, 0.26, 0.24),
            ),
        )

        /** The old four-table dev map is replaced; a user-edited map is kept. */
        private fun isLegacyStarterMap(stored: id.warung.monitor.feature.tables.data.StoredTableConfig): Boolean {
            val labels = stored.config.tables.map { it.label.value }
            return labels == listOf("Meja 1", "Meja 2", "Meja 3", "Meja 4")
        }

        private fun table(
            id: String,
            label: String,
            x: Double,
            y: Double,
            width: Double,
            height: Double,
        ): TableConfig {
            val points = listOf(
                NormalizedPoint(x, y),
                NormalizedPoint(x + width, y),
                NormalizedPoint(x + width, y + height),
                NormalizedPoint(x, y + height),
            )
            return TableConfig(
                id = TableId(id),
                label = TableLabel(label),
                polygon = TablePolygon.orNull(points) ?: error("dev table polygon is invalid"),
            )
        }
    }
}
