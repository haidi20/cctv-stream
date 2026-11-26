package id.warung.monitor.feature.onboarding.domain

/** Stable native codes from `docs/device-compatibility.md` section 4. */
object DeviceErrorCode {
    const val ABI_UNSUPPORTED: String = "ERR_ABI_UNSUPPORTED"
    const val ANDROID_TOO_OLD: String = "ERR_ANDROID_TOO_OLD"
    const val LIBRARY_LOAD: String = "ERR_LIBRARY_LOAD"
    const val MODEL_MISSING: String = "ERR_MODEL_MISSING"
    const val MODEL_CHECKSUM: String = "ERR_MODEL_CHECKSUM"
    const val ONNX_INIT: String = "ERR_ONNX_INIT"
    const val WARMUP_FAILED: String = "ERR_WARMUP_FAILED"
    const val RAM_LOW: String = "ERR_RAM_LOW"
    const val STORAGE_LOW: String = "ERR_STORAGE_LOW"
}

/** Result of loading the model and running one synthetic frame. */
sealed interface WarmUpResult {
    data class Passed(val elapsedMs: Long) : WarmUpResult

    data class Failed(
        val code: String,
        val detail: String,
    ) : WarmUpResult

    /** No model is bundled in this build; the app can still run in demo mode. */
    data object Skipped : WarmUpResult
}