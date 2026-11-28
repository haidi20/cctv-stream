package id.warung.monitor.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.warung.monitor.core.Engine
import id.warung.monitor.core.EngineConfig
import id.warung.monitor.core.NoHandle
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
import id.warung.monitor.data.DeviceSettingsStore
import id.warung.monitor.data.StoredDeviceSettings
import id.warung.monitor.di.EngineProvider
import id.warung.monitor.feature.cameras.data.InMemoryCameraRepository
import id.warung.monitor.feature.cameras.session.CameraSessionResult
import id.warung.monitor.feature.cameras.session.CountingReason
import id.warung.monitor.feature.cameras.session.CountingState
import id.warung.monitor.feature.onboarding.data.OnboardingRecord
import id.warung.monitor.feature.onboarding.data.OnboardingStore
import id.warung.monitor.feature.onboarding.domain.DeviceProfile
import id.warung.monitor.feature.tables.data.InMemoryTableConfigRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reason the engine is not counting must survive the trip to the UI.
 *
 * `RustCameraSession` is the only place that knows *why* `Engine.start` was not
 * called; before this test it dropped that decision on the floor and Home showed
 * a bare em dash (`docs/kotlin-rust-gaps.md` G-02).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RustCameraSessionCountingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val cameraId = CameraId("cam-1")

    private fun camera(source: CameraSource = rtsp()) = CameraProfile(
        id = cameraId,
        label = CameraLabel("Kamera"),
        source = source,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun rtsp() = CameraSource.Rtsp(host = "192.168.1.10", port = 554, path = "/stream")

    private fun tables(count: Int = 1): TableConfigSet {
        val polygon = TablePolygon.orNull(
            listOf(
                NormalizedPoint(0.1, 0.1),
                NormalizedPoint(0.3, 0.1),
                NormalizedPoint(0.3, 0.3),
            ),
        ) ?: error("fixture polygon invalid")
        return TableConfigSet(
            frame = MappingFrame(1280, 720, "16:9"),
            tables = (1..count).map { index ->
                TableConfig(
                    id = TableId("meja-%02d".format(index)),
                    label = TableLabel("Meja $index"),
                    polygon = polygon,
                )
            },
        )
    }

    /** Paths are a test seam: no asset or real `.onnx` is needed here. */
    private fun paths(model: String? = "model.onnx", runtime: String? = "libonnxruntime.so") =
        object : EngineAssetPaths(context) {
            override fun modelPath(): String? = model
            override fun onnxRuntimePath(): String? = runtime
        }

    private class FixedLoad(private val ramMb: Long?) : DeviceLoadSource {
        override fun thermalStatus(): Int? = null
        override fun availableRamMb(): Long? = null
        override fun ramTotalMb(): Long? = ramMb
    }

    private object NoOnboarding : OnboardingStore {
        override suspend fun load(): OnboardingRecord? = null
        override suspend fun save(record: OnboardingRecord) = Unit
    }

    private object NoSettings : DeviceSettingsStore {
        override suspend fun load(): StoredDeviceSettings? = null
        override suspend fun save(settings: StoredDeviceSettings) = Unit
    }

    /** Records whether `start` was reached and can fail on purpose. */
    private class FakeEngine(
        private val failure: Throwable? = null,
    ) : Engine(NoHandle) {
        var started = false
        var lastConfig: EngineConfig? = null

        override fun start(config: EngineConfig) {
            started = true
            lastConfig = config
            failure?.let { throw it }
        }

        override fun stop() = Unit
    }

    private fun session(
        engine: Engine,
        tables: InMemoryTableConfigRepository = InMemoryTableConfigRepository(),
        paths: EngineAssetPaths = paths(),
        ramMb: Long? = 8_000L,
    ): RustCameraSession {
        val provider = EngineProvider()
        provider.loaderForTest = { engine }
        return RustCameraSession(
            engineProvider = provider,
            cameras = InMemoryCameraRepository(listOf(camera())),
            tables = tables,
            assets = paths,
            planner = EngineStartPlanner(),
            onboarding = NoOnboarding,
            budget = InferenceBudget(),
            loadSource = FixedLoad(ramMb),
            deviceSettings = NoSettings,
        )
    }

    @Test
    fun a_ready_session_reports_active_and_starts_the_engine() = runTest {
        val engine = FakeEngine()
        val repo = InMemoryTableConfigRepository()
        repo.save(cameraId.value, tables())

        val result = session(engine, tables = repo).open(cameraId)

        assertEquals(CameraSessionResult.Opened(CountingState.Active), result)
        assertTrue(engine.started)
    }

    @Test
    fun a_camera_without_a_saved_map_reports_no_tables_and_never_starts() = runTest {
        val engine = FakeEngine()

        val result = session(engine).open(cameraId)

        assertEquals(
            CameraSessionResult.Opened(CountingState.Inactive(CountingReason.NoTables)),
            result,
        )
        assertEquals(false, engine.started)
    }

    @Test
    fun a_missing_model_is_reported_as_model_missing() = runTest {
        val engine = FakeEngine()
        val repo = InMemoryTableConfigRepository()
        repo.save(cameraId.value, tables())

        val result = session(engine, tables = repo, paths = paths(model = null)).open(cameraId)

        assertEquals(
            CameraSessionResult.Opened(CountingState.Inactive(CountingReason.ModelMissing)),
            result,
        )
        assertEquals(false, engine.started)
    }

    @Test
    fun a_missing_runtime_is_reported_as_runtime_missing() = runTest {
        val engine = FakeEngine()
        val repo = InMemoryTableConfigRepository()
        repo.save(cameraId.value, tables())

        val result = session(engine, tables = repo, paths = paths(runtime = null)).open(cameraId)

        assertEquals(
            CameraSessionResult.Opened(CountingState.Inactive(CountingReason.RuntimeMissing)),
            result,
        )
        assertEquals(false, engine.started)
    }

    @Test
    fun too_little_ram_is_reported_as_ram_too_low() = runTest {
        val engine = FakeEngine()
        val repo = InMemoryTableConfigRepository()
        repo.save(cameraId.value, tables())

        val result = session(engine, tables = repo, ramMb = 2_048L).open(cameraId)

        assertEquals(
            CameraSessionResult.Opened(CountingState.Inactive(CountingReason.RamTooLow)),
            result,
        )
        assertEquals(false, engine.started)
    }

    @Test
    fun a_demo_camera_is_reported_as_demo() = runTest {
        val engine = FakeEngine()
        val repo = InMemoryTableConfigRepository()
        repo.save(cameraId.value, tables())
        val provider = EngineProvider()
        provider.loaderForTest = { engine }
        val demo = RustCameraSession(
            engineProvider = provider,
            cameras = InMemoryCameraRepository(listOf(camera(CameraSource.Demo))),
            tables = repo,
            assets = paths(),
            planner = EngineStartPlanner(),
            onboarding = NoOnboarding,
            budget = InferenceBudget(),
            loadSource = FixedLoad(8_000L),
            deviceSettings = NoSettings,
        )

        val result = demo.open(cameraId)

        assertEquals(
            CameraSessionResult.Opened(CountingState.Inactive(CountingReason.Demo)),
            result,
        )
        assertEquals(false, engine.started)
    }

    @Test
    fun a_failed_start_reports_start_failed_not_active() = runTest {
        val engine = FakeEngine(failure = IllegalStateException("boom"))
        val repo = InMemoryTableConfigRepository()
        repo.save(cameraId.value, tables())

        val result = session(engine, tables = repo).open(cameraId)

        assertEquals(
            CameraSessionResult.Opened(CountingState.Inactive(CountingReason.StartFailed)),
            result,
        )
        assertTrue(engine.started)
    }

    @Test
    fun the_reason_is_also_published_on_the_flow() = runTest {
        val engine = FakeEngine()
        val repo = InMemoryTableConfigRepository()
        repo.save(cameraId.value, tables())
        val subject = session(engine, tables = repo)

        subject.open(cameraId)

        assertEquals(CountingState.Active, subject.counting.value)
    }

    @Test
    fun closing_the_session_forgets_the_reason() = runTest {
        val engine = FakeEngine()
        val repo = InMemoryTableConfigRepository()
        repo.save(cameraId.value, tables())
        val subject = session(engine, tables = repo)
        subject.open(cameraId)

        subject.close()

        assertEquals(CountingState.Unknown, subject.counting.value)
    }
}
