package id.warung.monitor.engine

import id.warung.monitor.core.Engine
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.Redaction
import id.warung.monitor.data.DeviceSettingsStore
import id.warung.monitor.data.StoredDeviceSettings
import id.warung.monitor.di.EngineProvider
import id.warung.monitor.feature.cameras.data.CameraRepository
import id.warung.monitor.feature.cameras.session.CameraSession
import id.warung.monitor.feature.cameras.session.CameraSessionResult
import id.warung.monitor.feature.cameras.session.CountingReason
import id.warung.monitor.feature.cameras.session.CountingState
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
 * totals stay an em dash until a start succeeds, and [counting] names the exact
 * prerequisite that is missing so Home never shows an unexplained dash
 * (`docs/kotlin-rust-gaps.md` G-02).
 */
@Singleton
class RustCameraSession @Inject constructor(
    private val engineProvider: EngineProvider,
    private val cameras: CameraRepository,
    private val tables: TableConfigRepository,
    private val assets: EngineAssetPaths,
    private val planner: EngineStartPlanner,
    private val onboarding: OnboardingStore,
    private val budget: InferenceBudget,
    private val loadSource: DeviceLoadSource,
    private val deviceSettings: DeviceSettingsStore,
) : CameraSession {

    private val mutex = Mutex()
    private val _activeCameraId = MutableStateFlow<CameraId?>(null)

    private val _counting = MutableStateFlow<CountingState>(CountingState.Unknown)

    /** Tuning the running session was started with; null while stopped. */
    private var activeTuning: EngineTuning? = null
    override val activeCameraId: StateFlow<CameraId?> = _activeCameraId.asStateFlow()
    override val counting: StateFlow<CountingState> = _counting.asStateFlow()

    override suspend fun open(cameraId: CameraId): CameraSessionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _activeCameraId.value
            val engine = engineProvider.engineOrNull()
            if (current == cameraId) {
                val counting = if (engine != null) {
                    startIfReady(engine, cameraId)
                } else {
                    CountingState.Inactive(CountingReason.RuntimeMissing)
                }
                publishCounting(counting)
                return@withLock CameraSessionResult.Opened(counting)
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
            val counting = engine?.let { startIfReady(it, cameraId) }
                ?: CountingState.Inactive(CountingReason.RuntimeMissing)
            publishCounting(counting)
            CameraSessionResult.Opened(counting)
        }
    }

    /**
     * Publishes one decision. `Unknown` is never published: a caller that asks
     * "why" always gets a cause, and a session that is open without a reason
     * would reintroduce the silent dash this guard exists to remove.
     */
    private fun publishCounting(counting: CountingState) {
        if (counting == CountingState.Unknown) return
        _counting.value = counting
    }

    /**
     * Starts only when every prerequisite exists and reports which one was
     * missing otherwise. No message here contains a path or a credential; the
     * caller only forwards a [CountingReason].
     *
     * A failed start is not a session failure: the map stays editable and the
     * preview keeps running, so the result is `Inactive(StartFailed)` rather
     * than an exception.
     */
    private suspend fun startIfReady(engine: Engine, cameraId: CameraId): CountingState {
        val camera = runCatching { cameras.find(cameraId) }.getOrNull()
            ?: return CountingState.Inactive(CountingReason.StartFailed)
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
        }.getOrNull() ?: return CountingState.Inactive(CountingReason.StartFailed)
        val config = when (plan) {
            is EngineStartPlan.Ready -> plan.config
            is EngineStartPlan.Skipped -> return plan.toCountingState()
            is EngineStartPlan.Unavailable -> return plan.toCountingState()
        }
        // Publish the rate before start so the pump never captures faster than
        // Rust samples. The pump reads this on its next tick.
        budget.setRequestedFps(config.inferenceFps)
        activeTuning = EngineTuning(config.inferenceFps, config.intraThreads)
        return try {
            engine.start(config)
            CountingState.Active
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Stay stopped; a failed start leaves the engine usable for the next
            // camera or the next attempt after the map is saved. The pump
            // keeps the last rate, which is harmless because health() reports
            // not-ready and no frame is captured.
            CountingState.Inactive(CountingReason.StartFailed)
        }
    }

    /**
     * Planner capped to the stored device profile's inference budget.
     *
     * The 2 GB threshold from `docs/performance.md` is applied on top of the
     * stored profile, so a phone whose RAM reading is lower than the record
     * that produced the profile is still protected. A missing reading keeps
     * the planner's current rate instead of inventing a throttle.
     */
    private suspend fun profileAdjustedPlanner(): EngineStartPlanner {
        val profile = runCatching { onboarding.load()?.profile }.getOrNull()
        val base = when (profile) {
            DeviceProfile.Lite -> planner.withInferenceFps(EngineStartPlanner.LITE_INFERENCE_FPS)
            else -> planner
        }
        val ramMb = runCatching { loadSource.ramTotalMb() }.getOrNull()
        val fps = EngineStartPlanner.inferenceFpsFor(
            ramTotalMb = ramMb,
            requested = base.inferenceFps,
        )
        // A phone below the hard floor gets a rate of 0, and `plan` then
        // returns `Unavailable`; `startIfReady` returns without calling
        // `Engine.start`, so no inference runs at an unsustainable rate.
        if (fps <= 0.0) return base.withInferenceFps(0.0)

        // The last session's p95 decides whether the profile rate actually
        // fits a frame slot on this phone. A missing row (first install) or a
        // missing p95 (no frame was sampled) keeps the profile rate.
        val stored = runCatching { deviceSettings.load() }.getOrNull()
        val tuning = EngineTuning.of(
            p95Ms = stored?.p95InferenceMs,
            requestedFps = fps,
            requestedThreads = stored?.tuning?.intraThreads ?: EngineStartPlanner.INTRA_THREADS,
        )
        return base.withTuning(tuning)
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val engine = engineProvider.engineOrNull()
            recordMeasuredTuning(engine)
            if (engine != null) {
                try {
                    engine.stop()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Best effort: the session must still report itself closed.
                }
            }
            budget.reset()
            activeTuning = null
            _activeCameraId.value = null
            _counting.value = CountingState.Unknown
        }
    }

    /**
     * Persists the p95 this session actually measured, so the next start can
     * choose a rate that fits instead of repeating the same guess.
     *
     * Best effort on purpose: a failed read or write must never block closing a
     * camera. A session that sampled no frame keeps the previous row rather
     * than overwriting a real measurement with "unknown".
     */
    private suspend fun recordMeasuredTuning(engine: Engine?) {
        val tuning = activeTuning ?: return
        if (engine == null) return
        val health = runCatching { engine.health() }.getOrNull() ?: return
        if (health.framesSampled == 0uL) return
        val p95 = health.inferenceP95Ms.takeIf { it > 0uL }?.toLong() ?: return
        runCatching {
            deviceSettings.save(
                StoredDeviceSettings(
                    tuning = EngineTuning(tuning.inferenceFps, tuning.intraThreads),
                    p95InferenceMs = p95,
                ),
            )
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
                    // The decision is published so a failed start after a save
                    // is explained instead of leaving a bare em dash.
                    if (runningCamera.isEmpty()) {
                        val counting = runCatching {
                            startIfReady(engine, CameraId(cameraId))
                        }.getOrNull()
                        if (counting != null) publishCounting(counting)
                    }
                    TableApplyResult.Applied
                } catch (error: UnsatisfiedLinkError) {
                    TableApplyResult.Unavailable(Redaction.message(error.message))
                } catch (error: NoClassDefFoundError) {
                    TableApplyResult.Unavailable(Redaction.message(error.message))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    classifyTableApplyError(error)
                }
            }
        }
}
