package id.warung.monitor.engine

import android.content.Context
import android.os.Build
import android.os.Bundle
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
import id.warung.monitor.core.EngineConfig
import id.warung.monitor.core.TableStatus
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.model.TableConfigJson
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
 * Resource soak on a real device, driven by a pushed clip.
 *
 * This is the instrumented half of `tools/device-soak.ps1`: Media3 decodes the
 * clip into the production `PlayerView` texture, the production
 * [EngineFramePump] feeds the real Rust engine and ONNX model at the documented
 * 2 FPS, and one CSV row is written per interval to
 * `Android/data/id.warung.monitor/files/cctv-soak.csv`.
 *
 * The engine counters (`frames_*`, `rss_kb`, p50/p95) only exist inside this
 * process, which is why the sampler runs here instead of in PowerShell
 * `dumpsys` parsing. The script supplies the duration and pulls the file.
 *
 * Skipped when the device has no model, runtime, or pushed clip, so a
 * device-less CI run stays green without pretending to have measured anything.
 */
class DeviceSoakTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val assets = EngineAssetPaths(context)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun the_monitoring_pipeline_is_sampled_until_the_requested_duration_ends() = runBlocking {
        prepareMedia()
        val model = assets.modelPath()
        assumeTrue("push the model first (tools/device-soak.ps1)", model != null)
        val runtime = assets.onnxRuntimePath()
        assumeTrue("libonnxruntime.so missing", runtime != null)
        assumeTrue("the device must support arm64-v8a", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val video = File(videoDir(), DevSeed.DEV_VIDEO_NAME)
        assumeTrue("push the dev clip first (tools/device-soak.ps1)", video.isFile)

        val args = InstrumentationRegistry.getArguments()
        val durationMin = args
            .getString("soakDurationMin")
            ?.toIntOrNull()
            ?.takeIf { it in 1..(12 * 60) }
            ?: DEFAULT_DURATION_MIN
        val intervalSec = args
            .getString("soakIntervalSec")
            ?.toIntOrNull()
            ?.takeIf { it in 10..600 }
            ?: DEFAULT_INTERVAL_SEC

        val provider = EngineProvider()
        val engine = provider.engineOrNull()
        assumeTrue("libcore_ffi.so could not be loaded", engine != null)
        engine!!.start(
            EngineConfig(
                cameraId = DevSeed.DEV_CAMERA_ID.value,
                modelPath = model!!,
                onnxRuntimePath = runtime!!,
                tablesJson = TableConfigJson.encode(DevSeed.starterConfig(), prettyPrint = false),
                inferenceFps = SOAK_FPS,
                debounceMs = DEBOUNCE_MS,
                intraThreads = 0u,
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
        budget.setRequestedFps(SOAK_FPS)
        val pump = EngineFramePump(
            engineProvider = provider,
            cadence = InferenceCadence(),
            loadSource = AndroidDeviceLoadSource(context),
            budget = budget,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sampler = SoakDeviceSampler(context)
        val external = context.getExternalFilesDir(null)
        assumeTrue("external files dir unavailable", external != null)
        val csv = File(external!!, CSV_NAME)
        val report = File(external, REPORT_NAME)
        csv.writeText(SoakCsv.HEADER + "\n")

        val samples = ArrayList<SoakSample>()
        val notes = StringBuilder()
        val startedAt = SystemClock.elapsedRealtime()
        var stopReason = "duration"
        var tablesChanged = false

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var playerView: PlayerView? = null
            scenario.onActivity { activity ->
                // A device under test is often left on its keyguard. A window
                // shown above the lockscreen still gets a live TextureView.
                activity.window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                )
                val view = LayoutInflater.from(activity)
                    .inflate(R.layout.preview_player, FrameLayout(activity), false) as PlayerView
                view.setShutterBackgroundColor(android.graphics.Color.BLACK)
                // The harness holds the display awake for the whole run: a
                // locked screen tears down the capture texture and silently
                // stops frames. Battery drain is therefore an upper bound; the
                // screen-off half of docs/performance.md 10.3 stays manual.
                activity.window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                )
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

            // One warm-up sample gets the baseline after the session and the
            // first tensors exist; it is still written so a 1-minute run has data.
            val captureJob = scope.launch {
                pump.run(
                    cameraId = DevSeed.DEV_CAMERA_ID.value,
                    textureProvider = { captureTarget },
                    keepRunning = { preview.state.value is PreviewState.Playing },
                )
            }
            try {
                val durationMs = durationMin * 60_000L
                var nextAtMs = 0L
                while (true) {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    if (elapsed >= durationMs) break
                    if (elapsed < nextAtMs) {
                        SystemClock.sleep((nextAtMs - elapsed).coerceAtMost(SAMPLE_TICK_MS))
                        continue
                    }
                    val health = runCatching { engine.health() }.getOrNull()
                    val sample = sampler.sample(elapsed / 1000L, health)
                    samples.add(sample)
                    csv.appendText(SoakCsv.row(sample) + "\n")
                    if (!tablesChanged && runCatching { engine.tables() }.getOrNull()
                            ?.any { it.status != TableStatus.UNKNOWN } == true
                    ) {
                        tablesChanged = true
                    }
                    if (sample.batteryTempC != null && sample.batteryTempC >= MAX_TEMP_C) {
                        stopReason = "battery_temp>=${MAX_TEMP_C.toInt()}C"
                        break
                    }
                    if (sample.thermalStatus == "SEVERE" || sample.thermalStatus == "CRITICAL" ||
                        sample.thermalStatus == "EMERGENCY" || sample.thermalStatus == "SHUTDOWN"
                    ) {
                        stopReason = "thermal=${sample.thermalStatus}"
                        break
                    }
                    nextAtMs += intervalSec * 1_000L
                }
            } finally {
                captureJob.cancel()
            }
        }

        val finalHealth = runCatching { engine.health() }.getOrNull()
        val finalTablesStatus = runCatching { engine.tables() }.getOrNull()
            ?.groupingBy { it.status.name }
            ?.eachCount()
            ?.toString()
            ?: "unknown"
        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        notes.appendLine("device=${Build.MODEL} api=${Build.VERSION.SDK_INT} ramMb=${totalRamMb()}")
        notes.appendLine(
            "durationMin=${durationMin} intervalSec=${intervalSec} samples=${samples.size} " +
                "stop=$stopReason fps=$SOAK_FPS",
        )
        notes.appendLine(
            "pssKb=${first?.pssKb}->${last?.pssKb} rssKb=${first?.rustRssKb}->${last?.rustRssKb} " +
                "cpuPct=${first?.cpuPercent}->${last?.cpuPercent}",
        )
        notes.appendLine(
            "battery=${first?.batteryLevelPercent}%->${last?.batteryLevelPercent}% " +
                "tempC=${first?.batteryTempC}->${last?.batteryTempC} " +
                "dataKb=${first?.dataKb}->${last?.dataKb}",
        )
        notes.appendLine(
            "finalHealth: modelReady=${finalHealth?.modelReady} " +
                "framesIn=${finalHealth?.framesIn} sampled=${finalHealth?.framesSampled} " +
                "dropped=${finalHealth?.framesDropped} " +
                "p50=${finalHealth?.inferenceP50Ms} p95=${finalHealth?.inferenceP95Ms} " +
                "statuses=$finalTablesStatus",
        )
        notes.appendLine("tablesChanged=$tablesChanged")

        runCatching { preview.close() }
        runCatching { engine.stop() }
        scope.cancel()
        report.writeText(notes.toString())
        println(notes.toString())
        instrumentation.sendStatus(0, Bundle().apply { putString("cctv-soak", notes.toString()) })

        // A run that wrote no row measured nothing; do not report success.
        assertTrue("the soak wrote no CSV row", samples.isNotEmpty())
        assertTrue("the CSV must contain at least one data row", csv.readLines().size >= 2)
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

    private fun totalRamMb(): Long {
        val info = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
        return info.totalMem / (1024L * 1024L)
    }

    companion object {
        private const val EXTERNAL_MODEL_NAME = "yolo11n.onnx"
        private const val CSV_NAME = "cctv-soak.csv"
        private const val REPORT_NAME = "cctv-soak.txt"
        private const val DEFAULT_DURATION_MIN = 30
        private const val DEFAULT_INTERVAL_SEC = 60
        private const val MAX_TEMP_C = 48.0

        /** Documented operating rate: `docs/performance.md` section 1. */
        private const val SOAK_FPS = 2.0

        /** Long enough that a looping clip is not flickering status. */
        private const val DEBOUNCE_MS = 2_000uL
        private const val POLL_MS = 100L

        /** Coarse wait between samples; 60 s intervals do not need 100 ms polling. */
        private const val SAMPLE_TICK_MS = 1_000L
        private const val PLAYBACK_TIMEOUT_MS = 20_000L
    }
}
