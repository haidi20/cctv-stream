package id.warung.monitor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preset is the only detection knob a warung owner sees
 * (`docs/detection-quality-todo.md` Q-05), so its stored name and its number
 * must both be stable.
 */
class DetectionPresetTest {

    @Test
    fun a_fresh_install_and_an_unknown_value_both_resolve_to_balanced() {
        assertEquals(DetectionPreset.Balanced, DetectionPreset.DEFAULT)
        assertNull(DetectionPreset.parse(null))
        assertNull(DetectionPreset.parse(""))
        assertNull(DetectionPreset.parse("sangat-presisi"))
    }

    @Test
    fun the_stored_names_round_trip() {
        DetectionPreset.entries.forEach { preset ->
            assertEquals(preset, DetectionPreset.parse(preset.wireName))
        }
    }

    @Test
    fun a_surrounding_space_in_storage_still_parses() {
        assertEquals(DetectionPreset.Precise, DetectionPreset.parse(" presisi "))
    }

    @Test
    fun the_presets_keep_the_thresholds_the_document_names() {
        assertEquals(0.35f, DetectionPreset.Balanced.confidenceThreshold, 0.0f)
        assertEquals(0.45f, DetectionPreset.Precise.confidenceThreshold, 0.0f)
    }

    @Test
    fun a_stricter_preset_never_lowers_the_threshold() {
        val thresholds = DetectionPreset.entries.map { it.confidenceThreshold }
        assertTrue("thresholds must not invert", thresholds == thresholds.sorted())
    }

    @Test
    fun every_preset_stays_inside_the_ffi_range() {
        DetectionPreset.entries.forEach { preset ->
            assertTrue(preset.confidenceThreshold in 0.0f..1.0f)
        }
    }
}
