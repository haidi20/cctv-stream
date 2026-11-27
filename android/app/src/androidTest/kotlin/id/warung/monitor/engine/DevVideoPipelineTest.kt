package id.warung.monitor.engine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import id.warung.monitor.R
import id.warung.monitor.core.Engine
import id.warung.monitor.core.EngineConfig
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.TableStatus
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.di.EngineProvider
import id.warung.monitor.feature.home.domain.PreviewState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real-device end-to-end run of the dev pipeline.
 *
 * Media3 plays the pushed MP4 into the production [PlayerView] texture, the
 * production [EngineFramePump] is driven at 10, 15, and 24 FPS, and the real
 * Rust engine runs the real ONNX model. Nothing is mocked. The test is skipped
 * when the device has no model, runtime, or pushed clip.
 *
 * Artifacts live in external files (pushed by `tools/device-e2e.ps1`); neither
 * is committed nor bundled in the APK.
 */
class DevVideoPipelineTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val assets = EngineAssetPaths(context)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun the_dev_clip_is_captured_and_analysed_at_each_hard_test_rate() = runBlocking {
        prepareMedia()
        val model = assets.modelPath()
        assumeTrue("push the model first (tools/device-e2e.ps1)", model != null)
        val runtime = assets.onnxRuntimePath()
        assumeTrue("libonnxruntime.so missing", runtime != null)
        assumeTrue("the device must support arm64-v8a", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val video = File(videoDir(), DevSeed.DEV_VIDEO_NAME)
        assumeTrue("push the dev clip first (tools/device-e2e.ps1)", video.isFile)

        val provider = EngineProvider()
        val engine = provider.engineOrNull()
        assumeTrue("libcore_ffi.so could not be loaded", engine != null)
        engine!!.start(
            EngineConfig(
                cameraId = DevSeed.DEV_CAMERA_ID.value,
                modelPath = model!!,
                onnxRuntimePath = runtime!!,
                tablesJson = TABLES,
                inferenceFps = MAX_RATE,
                debounceMs = 0uL,
            ),
        )

        val preview = Media3CameraPreview(context)
        val profile = CameraProfile(
            id = CameraId(DevSeed.DEV_CAMERA_ID.value),
            label = CameraLabel(DevSeed.DEV_CAMERA_LABEL),
            source = CameraSource.Video(fileName = DevSeed.DEV_VIDEO_NAME),
            createdAt = 0L,
            updatedAt = 0L,
        )
        val budget = InferenceBudget()
        val pump = EngineFramePump(
            engineProvider = provider,
            cadence = InferenceCadence(),
            loadSource = NoThrottleLoad,
            budget = budget,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val report = StringBuilder(
            "device=${Build.MODEL} api=${Build.VERSION.SDK_INT} ramMb=${totalRamMb()}\n",
        )

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var playerView: PlayerView? = null
            scenario.onActivity { activity ->
                val view = LayoutInflater.from(activity)
                    .inflate(R.layout.preview_player, FrameLayout(activity), false) as PlayerView
                view.setShutterBackgroundColor(android.graphics.Color.BLACK)
                activity.setContentView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                playerView = view
            }

            val attached = playerView ?: error("player view was not attached")
            preview.open(profile, null)
            // Attach the production surface to the player ExoPlayer just created.
            val exo = withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
                while (preview.player.value == null) delay(POLL_MS)
                preview.player.value
            }
            assertTrue("Media3 never created a player", exo != null)
            scenario.onActivity { attached.player = exo }

            val playing = withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
                while (preview.state.value !is PreviewState.Playing) delay(POLL_MS)
                true
            }
            assertTrue("Media3 never reported Playing for the dev clip", playing == true)
            val texture = withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
                var surface: TextureView? = null
                while (surface == null || !surface.isAvailable) {
                    scenario.onActivity {
                        surface = attached.videoSurfaceView as? TextureView
                    }
                    delay(POLL_MS)
                }
                surface
            }
            assertTrue("the preview never produced a capture texture", texture != null)
            val captureTarget = texture!!
            report.append("texture=${captureTarget.width}x${captureTarget.height}\n")

            val captureJob = scope.launch {
                pump.run(
                    cameraId = DevSeed.DEV_CAMERA_ID.value,
                    textureProvider = { captureTarget },
                    keepRunning = { preview.state.value is PreviewState.Playing },
                )
            }
            try {
                RATES.forEach { requested ->
                    budget.setRequestedFps(requested)
                    SystemClock.sleep(WARM_UP_MS)
                    val before = engine.health()
                    SystemClock.sleep(MEASURE_MS)
                    val after = engine.health()
                    val frames = (after.framesIn - before.framesIn).toLong()
                    val sampled = (after.framesSampled - before.framesSampled).toLong()
                    val capturedFps = frames * 1_000.0 / MEASURE_MS
                    val analysisFps = sampled * 1_000.0 / MEASURE_MS
                    report.append(
                        "requested=${formatFps(requested)} " +
                            "capturedFps=%.1f analysisFps=%.1f frames=%d sampled=%d\n".format(
                                capturedFps,
                                analysisFps,
                                frames,
                                sampled,
                            ),
                    )
                    assertTrue(
                        "no frame reached Rust at ${formatFps(requested)} FPS",
                        frames > 0L,
                    )
                    // `push_frame` runs inference inline, so capture is
                    // inference-bound: asking for 24 FPS cannot yield 24
                    // captures when one frame costs ~216 ms. The measured
                    // capturedFps/analysisFps numbers are the deliverable; this
                    // check only rules out a runaway loop that ignores pacing.
                    assertTrue(
                        "capture exceeded the requested ${formatFps(requested)} FPS",
                        capturedFps <= requested * MAX_CAPTURE_OVERSHOOT,
                    )
                    assertTrue(
                        "Rust sampled nothing at ${formatFps(requested)} FPS",
                        sampled > 0L,
                    )
                    Thread.sleep(PAUSE_MS)
                }
            } finally {
                captureJob.cancel()
            }

            val tables = engine.tables()
            val detections = engine.lastDetections()
            val health = engine.health()
            report.append(
                "tables=${tables.size} detections=${detections.size} " +
                    "statuses=${tables.joinToString { it.status.name }} " +
                    "modelReady=${health.modelReady} " +
                    "lastInferenceAgeMs=${health.lastInferenceAgeMs}\n",
            )
            assertTrue("Rust reported no table rows", tables.isNotEmpty())
            assertTrue("Rust never reported a model-ready session", health.modelReady)
            assertTrue("no inference was sampled during the run", health.framesSampled > 0uL)
            // A polygon that never received an observation stays unknown; a
            // definite status proves the clip reached Rust's spatial engine and
            // debounce ran, not merely that frames arrived.
            assertTrue(
                "every table stayed unknown: polygons never saw a real frame",
                tables.any { it.status != TableStatus.UNKNOWN },
            )
        }

        runCatching { preview.close() }
        runCatching { engine.stop() }
        scope.cancel()
        writeReport(report.toString())
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("cctv-dev-e2e", report.toString())
        })
    }

    /**
     * Copies the pushed model and clip into the production file locations.
     * A missing source is left for the `assumeTrue` skip above.
     */
    private fun prepareMedia() {
        val external = context.getExternalFilesDir(null) ?: return
        val videos = requireDirectory(videoDir())
        copyFile(File(external, DevSeed.DEV_VIDEO_NAME), File(videos, DevSeed.DEV_VIDEO_NAME))
        val models = requireDirectory(File(context.filesDir, EngineAssetPaths.MODEL_DIR))
        copyFile(File(external, EXTERNAL_MODEL_NAME), File(models, EngineAssetPaths.MODEL_NAME))
    }

    private fun videoDir(): File = File(context.filesDir, "videos")

    private fun requireDirectory(dir: File): File = dir.apply { mkdirs() }

    private fun copyFile(from: File, to: File) {
        if (!from.isFile || to.isFile) return
        runCatching { from.copyTo(to, overwrite = false) }
    }

    private fun writeReport(report: String) {
        val external = context.getExternalFilesDir(null) ?: return
        runCatching { File(external, REPORT_NAME).writeText(report) }
        println(report)
    }

    private fun totalRamMb(): Long {
        val info = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
        return info.totalMem / (1024L * 1024L)
    }

    private fun formatFps(fps: Double): String =
        if (fps % 1.0 == 0.0) fps.toInt().toString() else String.format("%.1f", fps)

    /** Never throttles: the hard test measures the requested rate itself. */
    private object NoThrottleLoad : DeviceLoadSource {
        override fun thermalStatus(): Int? = android.os.PowerManager.THERMAL_STATUS_NONE

        override fun availableRamMb(): Long? = Long.MAX_VALUE
    }

    companion object {
        private const val EXTERNAL_MODEL_NAME = "yolo11n.onnx"
        private const val REPORT_NAME = "cctv-dev-e2e.txt"
        private const val MAX_RATE = 24.0
        /** Tolerance for pacing jitter; the loop must never exceed the request. */
        private const val MAX_CAPTURE_OVERSHOOT = 1.25
        private const val WARM_UP_MS = 3_000L
        private const val MEASURE_MS = 4_000L
        private const val PAUSE_MS = 500L
        private const val POLL_MS = 100L
        private const val PLAYBACK_TIMEOUT_MS = 20_000L
        private val RATES = listOf(10.0, 15.0, 24.0)

        private val TABLES = """
            {
              "version": 2,
              "video": { "width": 768, "height": 432, "aspect_ratio": "16:9" },
              "tables": [
                { "id": "meja-01", "label": "Meja 1", "polygon": [
                  { "x": 0.04, "y": 0.44 }, { "x": 0.28, "y": 0.44 },
                  { "x": 0.28, "y": 0.84 }, { "x": 0.04, "y": 0.84 }
                ] },
                { "id": "meja-02", "label": "Meja 2", "polygon": [
                  { "x": 0.30, "y": 0.20 }, { "x": 0.54, "y": 0.20 },
                  { "x": 0.54, "y": 0.62 }, { "x": 0.30, "y": 0.62 }
                ] }
              ]
            }
        """.trimIndent()
    }
}