package id.warung.monitor.engine

import android.os.SystemClock
import id.warung.monitor.core.EngineConfig
import id.warung.monitor.core.EngineException
import id.warung.monitor.di.EngineProvider
import id.warung.monitor.feature.onboarding.domain.DeviceErrorCode
import id.warung.monitor.feature.onboarding.domain.EngineWarmUp
import id.warung.monitor.feature.onboarding.domain.WarmUpResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads ONNX Runtime and the model through the existing `Engine.start`, then
 * runs one synthetic frame.
 *
 * `Engine.start` parses the table JSON and constructs the detector but never
 * opens RTSP, so this is a real warm-up without touching a camera. The engine is
 * always stopped afterwards: the diagnostic must not leave a session behind.
 *
 * Model paths never appear in a failure reason; only the stable native codes
 * from `device-compatibility.md` section 4 cross the module boundary. The pure
 * pieces live in [WarmUpProbe] so they are unit-testable on a host.
 */
@Singleton
class RustEngineWarmUp @Inject constructor(
    private val engineProvider: EngineProvider,
    private val assets: EngineAssetPaths,
) : EngineWarmUp {

    override suspend fun warmUp(): WarmUpResult = withContext(Dispatchers.IO) {
        val modelPath = assets.modelPath()
            ?: return@withContext WarmUpResult.Failed(
                code = DeviceErrorCode.MODEL_MISSING,
                detail = "Model tidak ditemukan di aset aplikasi",
            )
        val runtimePath = assets.onnxRuntimePath()
            ?: return@withContext WarmUpResult.Failed(
                code = DeviceErrorCode.ONNX_INIT,
                detail = "ONNX Runtime tidak ditemukan di paket aplikasi",
            )
        val engine = engineProvider.engineOrNull()
            ?: return@withContext WarmUpResult.Failed(
                code = DeviceErrorCode.LIBRARY_LOAD,
                detail = "Pustaka native gagal dimuat",
            )

        val startedAt = SystemClock.elapsedRealtime()
        try {
            engine.start(
                EngineConfig(
                    cameraId = WarmUpProbe.CAMERA_ID,
                    modelPath = modelPath,
                    onnxRuntimePath = runtimePath,
                    tablesJson = WarmUpProbe.tablesJson(),
                    inferenceFps = 1.0,
                    debounceMs = 0uL,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: EngineException.Model) {
            return@withContext WarmUpResult.Failed(
                code = WarmUpProbe.modelErrorCode(error.reason),
                detail = "Model atau runtime menolak dimuat",
            )
        } catch (_: EngineException.Config) {
            return@withContext WarmUpResult.Failed(
                code = DeviceErrorCode.ONNX_INIT,
                detail = "Konfigurasi warm-up ditolak runtime",
            )
        } catch (_: Throwable) {
            return@withContext WarmUpResult.Failed(
                code = DeviceErrorCode.ONNX_INIT,
                detail = "Runtime deteksi gagal disiapkan",
            )
        }

        try {
            val frame = ByteArray(WarmUpProbe.FRAME_SIZE * WarmUpProbe.FRAME_SIZE * 3)
            val inference = withTimeoutOrNull(FRAME_TIMEOUT_MS) {
                engine.pushFrame(
                    width = WarmUpProbe.FRAME_SIZE.toUInt(),
                    height = WarmUpProbe.FRAME_SIZE.toUInt(),
                    rgb = frame,
                )
            }
            if (inference == null) {
                WarmUpResult.Failed(
                    code = DeviceErrorCode.WARMUP_FAILED,
                    detail = "Inferensi uji melewati batas waktu",
                )
            } else {
                WarmUpResult.Passed(elapsedMs = SystemClock.elapsedRealtime() - startedAt)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: EngineException.Model) {
            WarmUpResult.Failed(
                code = WarmUpProbe.modelErrorCode(error.reason),
                detail = "Model gagal saat inferensi uji",
            )
        } catch (_: Throwable) {
            WarmUpResult.Failed(
                code = DeviceErrorCode.WARMUP_FAILED,
                detail = "Inferensi uji gagal",
            )
        } finally {
            runCatching { engine.stop() }
        }
    }

    private companion object {
        const val FRAME_TIMEOUT_MS = 10_000L
    }
}