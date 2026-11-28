package id.warung.monitor.core.sync

import id.warung.monitor.core.model.DetectionPreset

/**
 * The detection preset this device last chose
 * (`docs/detection-quality-todo.md` Q-05).
 *
 * One value for the whole phone, not per camera: the preset describes the
 * lighting and the lens the phone is pointed at, and it is what the next
 * `Engine.start` sends as `confidence_threshold`. It lives beside
 * [RetentionSettings] so `feature:settings` can read and write it without
 * depending on `app`.
 */
data class DetectionSetting(
    val preset: DetectionPreset,
    /**
     * True when storage held a name this build does not recognise.
     *
     * The screen shows a one-line note in that case: falling back silently
     * would look like the user's choice was ignored.
     */
    val unknownStoredValue: Boolean = false,
)

/** Read/write seam so the settings screen and the engine share one value. */
interface DetectionSettings {
    suspend fun load(): DetectionSetting

    suspend fun save(preset: DetectionPreset)
}
