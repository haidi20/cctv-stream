package id.warung.monitor.engine

import id.warung.monitor.core.Engine
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.Redaction
import id.warung.monitor.di.EngineProvider
import id.warung.monitor.feature.cameras.data.CameraRepository
import id.warung.monitor.feature.cameras.session.CameraSession
import id.warung.monitor.feature.cameras.session.CameraSessionResult
import id.warung.monitor.feature.onboarding.data.OnboardingStore
import id.warung.monitor.feature.onboarding.domain.DeviceProfile
import id.warung.monitor.feature.tables.data.TableConfigRepository
import id.warung.monitor.feature.tables.engine.TableApplyResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Enforces the one-camera-at-a-time rule on top of the single Rust engine.
 *
 * Opening another camera stops the previous engine before the new id becomes
 * active. A failed stop leaves the old session in place, so the UI never shows a
 * camera whose stream was never actually released. All engine calls run on
 * [Dispatchers.IO] and share one [Mutex], which also covers [applyTables] so a
 * map cannot be written while the session is switching cameras.
 *
 * Starting the engine is deliberately best-effort: a missing model, a missing
 * ONNX Runtime, or an empty map must not block mapping or the preview. The
 * totals stay an em dash until a start succeeds.
 */
@Singleton
class RustCameraSession @Inject constructor(
    private val engineProvider: EngineProvider,
    private val cameras: CameraRepository,
    private val tables: TableConfigRepository,
    private val assets: EngineAssetPaths,
    private val planner: EngineStartPlanner,
    private val onboarding: OnboardingStore,
) : CameraSession {

    private val mutex = Mutex()
    private val _activeCameraId = MutableStateFlow<CameraId?>(null)
    override val activeCameraId: StateFlow<CameraId?> = _activeCameraId.asStateFlow()

    override suspend fun open(cameraId: CameraId): CameraSessionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _activeCameraId.value
            val engine = engineProvider.engineOrNull()
            if (current == cameraId) {
                if (engine != null) startIfReady(engine, cameraId)
                return@withLock CameraSessionResult.Opened
            }

            if (engine != null) {
                try {
                    engine.stop()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    return@withLock CameraSessionResult.Failed(
                        "Gagal menutup sesi kamera sebelumnya: " +
                            Redaction.message(error.message ?: error.javaClass.simpleName),
                    )
                }
            }
            _activeCameraId.value = cameraId
            engine?.let { startIfReady(it, cameraId) }
            CameraSessionResult.Opened
        }
    }

    /**
     * Starts only when every prerequisite exists. The failure reason is kept
     * internal: it never contains a path, and [CameraSessionResult.Opened] is
     * still returned so the user can map and save the tables.
     */
    private suspend fun startIfReady(engine: Engine, cameraId: CameraId) {
        val camera = runCatching { cameras.find(cameraId) }.getOrNull() ?: return
        val stored = runCatching { tables.active(cameraId.value) }.getOrNull()
        // A `lite` phone runs fewer inferences so the CPU it borrows from the
        // app the user opens stays small. A missing or stale record keeps the
        // default rate: the onboarding check is advisory, not a precondition.
        val plan = runCatching {
            profileAdjustedPlanner().plan(
                camera = camera,
                stored = stored,
                modelPath = assets.modelPath(),
                runtimePath = assets.onnxRuntimePath(),
            )
        }.getOrNull() ?: return
        val config = when (plan) {
            is EngineStartPlan.Ready -> plan.config
            is EngineStartPlan.Skipped, is EngineStartPlan.Unavailable -> return
        }
        try {
            engine.start(config)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Stay stopped; a failed start leaves the engine usable for the next
            // camera or the next attempt after the map is saved.
        }
    }

    /** Planner capped to the stored device profile's inference budget. */
    private suspend fun profileAdjustedPlanner(): EngineStartPlanner {
        val profile = runCatching { onboarding.load()?.profile }.getOrNull()
        return when (profile) {
            DeviceProfile.Lite -> planner.withInferenceFps(EngineStartPlanner.LITE_INFERENCE_FPS)
            else -> planner
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val engine = engineProvider.engineOrNull()
            if (engine != null) {
                try {
                    engine.stop()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Best effort: the session must still report itself closed.
                }
            }
            _activeCameraId.value = null
        }
    }

    /**
     * Applies a table map only when [cameraId] owns the session and the running
     * engine agrees. A stopped engine is allowed: `setTables` is valid before
     * `start`, and the map is stored locally either way.
     */
    suspend fun applyTables(cameraId: String, configJson: String): TableApplyResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val active = _activeCameraId.value?.value
                if (active != cameraId) {
                    return@withLock TableApplyResult.WrongCamera(active)
                }
                val engine: Engine = engineProvider.engineOrNull()
                    ?: return@withLock TableApplyResult.Unavailable("Engine native tidak tersedia")
                try {
                    val runningCamera = engine.health().cameraId
                    if (runningCamera.isNotEmpty() && runningCamera != cameraId) {
                        return@withLock TableApplyResult.WrongCamera(runningCamera)
                    }
                    engine.setTables(configJson)
                    // A map saved while the engine was stopped is the normal
                    // first-run path: load the model now so Home can count.
                    if (runningCamera.isEmpty()) {
                        runCatching { startIfReady(engine, CameraId(cameraId)) }
                    }
                    TableApplyResult.Applied
                } catch (error: UnsatisfiedLinkError) {
                    TableApplyResult.Unavailable(Redaction.message(error.message))
                } catch (error: NoClassDefFoundError) {
                    TableApplyResult.Unavailable(Redaction.message(error.message))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    val message = Redaction.message(error.message ?: error.javaClass.simpleName)
                    val rejected = message.contains("tables", ignoreCase = true) ||
                        message.contains("config", ignoreCase = true)
                    if (rejected) {
                        TableApplyResult.Rejected(message)
                    } else {
                        TableApplyResult.Unavailable(message)
                    }
                }
            }
        }
}
