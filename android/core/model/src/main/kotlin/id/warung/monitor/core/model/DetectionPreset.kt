package id.warung.monitor.core.model

/**
 * How strict the person detector should be, as a choice a warung owner can
 * make without typing a float (`docs/detection-quality-todo.md` Q-05).
 *
 * Two presets only, on purpose. A raw confidence field would let a user pick
 * 0,05 and drown the screen in chair boxes, or 0,95 and lose every seated
 * person; these two values are the ones the document names. `min_box_px` and
 * the IoU threshold stay at their measured defaults until Q-03 produces a
 * precision/recall table from real footage.
 *
 * [wireName] is the stable string that reaches storage, so [label] can be
 * reworded without a migration.
 */
enum class DetectionPreset(
    val wireName: String,
    val confidenceThreshold: Float,
    val label: String,
) {
    /**
     * The detector default. Keeps people standing further from the camera and
     * accepts some furniture; that is the trade the field complaints describe.
     */
    Balanced("seimbang", 0.35f, "Seimbang"),

    /**
     * Higher bar. Fewer chair/table false positives, at the cost of distant or
     * partly hidden people.
     */
    Precise("presisi", 0.45f, "Presisi"),
    ;

    companion object {
        /** What a fresh install and an unknown stored value both resolve to. */
        val DEFAULT: DetectionPreset = Balanced

        /**
         * The preset named [name], or `null` when nothing usable is stored.
         *
         * `null` rather than [DEFAULT] so the caller can tell "never chosen"
         * apart from "a value this build does not understand" and say so on
         * screen instead of quietly rewriting the user's choice.
         */
        fun parse(name: String?): DetectionPreset? =
            entries.firstOrNull { it.wireName == name?.trim() }
    }
}
