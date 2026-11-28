package id.warung.monitor.feature.settings.domain

import id.warung.monitor.core.model.DetectionPreset

/**
 * State of the "Deteksi" screen (`docs/detection-quality-todo.md` Q-05).
 *
 * [savedPreset] is what storage holds and [preset] is what the user picked, so
 * the save button can stay disabled until the choice actually differs. Nothing
 * here restarts a running session: the preset reaches Rust on the next
 * `Engine.start`, which is what the note under the button says.
 */
data class DetectionState(
    val loading: Boolean = true,
    val preset: DetectionPreset = DetectionPreset.DEFAULT,
    val savedPreset: DetectionPreset = DetectionPreset.DEFAULT,
    /**
     * Storage held a name this build does not know, so the screen is showing
     * the fallback rather than the user's earlier choice.
     */
    val unknownStoredValue: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val hasChange: Boolean get() = preset != savedPreset

    val canSave: Boolean get() = !loading && !saving && hasChange

    /** Only meaningful while the unknown value has not been replaced yet. */
    val showsUnknownValueNote: Boolean get() = unknownStoredValue && !hasChange
}
