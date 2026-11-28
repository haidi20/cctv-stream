package id.warung.monitor.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import id.warung.monitor.core.Engine
import id.warung.monitor.core.EngineConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real-device timing for the Rust detector.
 *
 * This is a measurement harness, not a correctness test: it reports p50/p95
 * inference latency and the achievable rate for each resolution, so the
 * "hard test" FPS question is answered with device data instead of guesses.
 *
 * The model is either bundled as `assets/models/yolov11n.onnx` or pushed by
 * `tools/device-bench.ps1`; without it the test is skipped. Nothing here logs a
 * path, a credential, or a frame.
 */
class DeviceInferenceBenchmarkTest {

    private val assets = EngineAssetPaths(ApplicationProvider.getApplicationContext())

    @Test
    fun report_latency_and_sustainable_rate_per_resolution() {
        // The bench script pushes a model into external storage so the APK
        // stays free of a 10 MB artifact; production expects it under
        // files/models. Copy it once here so the measured path is the real one.
        val external = File(
            ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null),
            EXTERNAL_MODEL_NAME,
        )
        val internal = File(
            java.io.File(
                ApplicationProvider.getApplicationContext<Context>().filesDir,
                EngineAssetPaths.MODEL_DIR,
            ),
            EngineAssetPaths.MODEL_NAME,
        )
        if (!internal.isFile && external.isFile) {
            internal.parentFile?.mkdirs()
            external.copyTo(internal, overwrite = true)
        }
        val model = assets.modelPath()
        assumeTrue("push $EXTERNAL_MODEL_NAME first (tools/device-bench.ps1)", model != null)
        assumeTrue("the device must support arm64-v8a", Build.SUPPORTED_ABIS.contains("arm64-v8a"))

        val runtimePath = assets.onnxRuntimePath()
        assumeTrue("libonnxruntime.so missing", runtimePath != null)

        // `-e benchThreads 2` pins the ORT pool so the same run can compare
        // latency and CPU against the default (0). Costs are measured, not
        // guessed: see docs/performance.md section 6.3.
        val intraThreads = InstrumentationRegistry.getArguments()
            .getString("benchThreads")
            ?.toIntOrNull()
            ?.takeIf { it in 0..4 }
            ?: 0
        val results = RESOLUTIONS.map { (width, height) ->
            measure(width, height, model!!, runtimePath!!, intraThreads)
        }
        val smallModel = File(SMALL_MODEL).takeIf { it.isFile }?.let { file ->
            measure(SMALL_INPUT, SMALL_INPUT, file.absolutePath, runtimePath!!, intraThreads)
        }
        val allResults = if (smallModel != null) results + smallModel else results
        val report = buildString {
            appendLine("device=${Build.MODEL} api=${Build.VERSION.SDK_INT}")
            appendLine(
                "ramMb=${ramTotalMb()} thermal=${thermalStatus()} requestedThreads=$intraThreads",
            )
            allResults.forEach { r ->
                appendLine(
                    "${r.width}x${r.height}: p50=${r.p50Ms}ms p95=${r.p95Ms}ms " +
                        "max=${r.maxMs}ms fps_serial=${r.serialFps} fps_engine=${r.engineFps} " +
                        "sampled=${r.samples} ortThreads=${r.ortThreads} nice=${r.nice}",
                )
            }
        }
        // One assertion keeps the task from reporting success on an empty run;
        // the numbers themselves are the deliverable.
        allResults.forEach { assertTrue("no sample for ${it.width}x${it.height}", it.samples > 0) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("cctv-benchmark", report)
        })
        File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), REPORT_NAME)
            .writeText(report)
        println(report)
        assertTrue("the 640x640 resolution must be measured", allResults.any { it.width == 640 && it.height == 640 })
    }

    private fun measure(
        width: Int,
        height: Int,
        modelPath: String,
        runtimePath: String,
        intraThreads: Int,
    ): ResolutionResult {
        val engine = Engine()
        engine.start(
            EngineConfig(
                cameraId = "bench-${width}x$height".replace('x', '-'),
                modelPath = modelPath,
                onnxRuntimePath = runtimePath,
                tablesJson = TABLES,
                inferenceFps = 24.0,
                debounceMs = 0uL,
                intraThreads = intraThreads.toUInt(),
                confidenceThreshold = null,
                iouThreshold = null,
                minBoxPx = null,
            ),
        )
        val frame = syntheticFrame(width, height)
        // Warm up once so the first sample excludes session and allocator
        // setup, matching how the pump behaves after a preview starts.
        engine.pushFrame(width.toUInt(), height.toUInt(), frame)
        val durations = ArrayList<Long>(SERIAL_SAMPLES)
        // The engine throttles to inferenceFps=24, i.e. ~41 ms; a serial loop
        // paced at that rate measures the real achievable rate, not a burst.
        val intervalNanos = 1_000_000_000L / 24
        val started = SystemClock.elapsedRealtime()
        var lastDeadline = System.nanoTime()
        var sampled = engine.health().framesSampled
        var ortThreads = 0
        var nice = "none"
        while (durations.size < SERIAL_SAMPLES) {
            lastDeadline += intervalNanos
            val wait = lastDeadline - System.nanoTime()
            if (wait > 0) SystemClock.sleep(wait / 1_000_000)
            val t0 = System.nanoTime()
            engine.pushFrame(width.toUInt(), height.toUInt(), frame)
            val after = engine.health().framesSampled
            // A throttled call returns in microseconds and says nothing about
            // inference cost; only a call that sampled a frame has latency.
            val workerNice = inferenceThreadNice()
            if (workerNice.isNotEmpty()) {
                ortThreads = workerNice.size
                nice = workerNice.joinToString("/")
            }
            if (after > sampled) {
                durations.add((System.nanoTime() - t0) / 1_000_000)
            }
            sampled = after
        }
        val elapsedMs = SystemClock.elapsedRealtime() - started
        val health = engine.health()
        engine.stop()

        val sorted = durations.sorted()
        return ResolutionResult(
            width = width,
            height = height,
            samples = sorted.size,
            p50Ms = sorted[sorted.size / 2],
            p95Ms = sorted[(sorted.size * 95) / 100],
            maxMs = sorted.last(),
            serialFps = (SERIAL_SAMPLES * 1_000L / elapsedMs.coerceAtLeast(1)).toInt(),
            engineFps = (health.framesSampled.toLong() * 1_000L / elapsedMs.coerceAtLeast(1)).toInt(),
            ortThreads = ortThreads,
            nice = nice,
        )
    }

    private fun syntheticFrame(width: Int, height: Int): ByteArray {
        // Flat mid-grey: the box position does not matter for timing, and a
        // flat frame keeps the decode path identical between resolutions.
        return ByteArray(width * height * 3) { 128.toByte() }
    }

    /**
     * Nice value of the ORT worker threads.
     *
     * `BackgroundThreadManager` sets nice 10 so inference always loses to
     * whatever app the user opens; this reads `/proc/self/task/<tid>/comm` and the
     * 19th `stat` field instead of trusting the Rust unit test.
     */
    private fun inferenceThreadNice(): List<String> {
        val tasks = File("/proc/self/task").listFiles() ?: return emptyList()
        return tasks.mapNotNull { task ->
            val name = runCatching { File(task, "comm").readText().trim() }.getOrNull()
            if (name != ORT_WORKER_NAME) return@mapNotNull null
            val fields = runCatching {
                File(task, "stat").readText().trim().split(' ')
            }.getOrNull() ?: return@mapNotNull null
            fields.getOrNull(18)
        }
    }

    private fun ramTotalMb(): Long {
        val info = android.app.ActivityManager.MemoryInfo()
        ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(android.app.ActivityManager::class.java)
            ?.getMemoryInfo(info)
        return info.totalMem / (1024L * 1024L)
    }

    private fun thermalStatus(): Int =
        ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(PowerManager::class.java)
            ?.currentThermalStatus ?: -1

    private data class ResolutionResult(
        val width: Int,
        val height: Int,
        val samples: Int,
        val p50Ms: Long,
        val p95Ms: Long,
        val maxMs: Long,
        val serialFps: Int,
        val engineFps: Int,
        val ortThreads: Int,
        val nice: String,
    )

    companion object {
        private const val EXTERNAL_MODEL_NAME = "yolo11n.onnx"
        private const val REPORT_NAME = "cctv-benchmark.txt"
        private const val ORT_WORKER_NAME = "cctv-ort-worker"
        private const val SERIAL_SAMPLES = 60
        private const val SMALL_INPUT = 320
        private val SMALL_MODEL = File(
            java.io.File(
                ApplicationProvider.getApplicationContext<Context>().filesDir,
                EngineAssetPaths.MODEL_DIR,
            ),
            "yolov11n-320.onnx",
        ).absolutePath
        private val RESOLUTIONS = listOf(640 to 360, 640 to 640)
        private val TABLES = """
            {
              "version": 2,
              "video": { "width": 640, "height": 360, "aspect_ratio": "16:9" },
              "tables": [
                { "id": "meja-01", "polygon": [
                  { "x": 0.1, "y": 0.2 }, { "x": 0.5, "y": 0.2 },
                  { "x": 0.5, "y": 0.8 }, { "x": 0.1, "y": 0.8 }
                ] }
              ]
            }
        """.trimIndent()
    }
}
