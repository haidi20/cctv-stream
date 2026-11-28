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
         * Six footprints over a 768x432 frame, measured from the clip.
         *
         * This is the dev-mode stand-in for the manual mapping: in production
         * the user draws these by hand, and in dev the same blue scope has to
         * land on the six real tables or the overlay is judged against a lie.
         *
         * Derivation (`tools/onnx-test` has no model, so this is image
         * statistics, not a detector): background subtraction over all 400
         * frames of the clip produced 669 person-like blobs; their centroids
         * cluster into six seats, and a least-squares fit gave
         * `width = 0.0711 + 0.0001*y` and `height = 0.157 + 0.0002*y` in
         * normalised units. Each footprint is 1.75x the seated person's width
         * and 0.95x their height, centred 42% above the person's centre.
         *
         * The raw boxes overlapped slightly (meja-02/meja-03 by 0.072 x 0.008),
         * so each pair is trimmed along its shorter overlap axis, never moving a
         * person's centre outside its own footprint.
         *
         * Checked numerically: every footprint contains its own person centre,
         * all six stay inside the frame, the six are disjoint, they cover 20.9%
         * of the frame, and each holds real motion (2.7%-8.7% of total motion
         * energy). `DevSeedTest` guards the geometry; it cannot re-measure the
         * clip, so a clip change needs this derivation re-run.
         */
        fun starterConfig(): TableConfigSet = TableConfigSet(
            frame = MappingFrame(width = 768, height = 432, aspectRatio = "16:9"),
            tables = listOf(
                table("meja-01", "Meja 1", 0.030, 0.381, 0.125, 0.149),
                table("meja-02", "Meja 2", 0.280, 0.292, 0.124, 0.149),
                table("meja-03", "Meja 3", 0.377, 0.462, 0.125, 0.149),
                table("meja-04", "Meja 4", 0.645, 0.557, 0.125, 0.149),
                table("meja-05", "Meja 5", 0.629, 0.851, 0.125, 0.149),
                table("meja-06", "Meja 6", 0.838, 0.637, 0.125, 0.149),
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
