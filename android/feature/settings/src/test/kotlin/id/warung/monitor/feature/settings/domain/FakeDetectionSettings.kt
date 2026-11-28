package id.warung.monitor.feature.settings.domain

import id.warung.monitor.core.model.DetectionPreset
import id.warung.monitor.core.sync.DetectionSetting
import id.warung.monitor.core.sync.DetectionSettings

/** In-memory preset store; records writes so tests can assert what was saved. */
class FakeDetectionSettings(
    var preset: DetectionPreset = DetectionPreset.DEFAULT,
    var unknownStoredValue: Boolean = false,
    var failWrites: Boolean = false,
    var failReads: Boolean = false,
) : DetectionSettings {

    val saved = mutableListOf<DetectionPreset>()

    override suspend fun load(): DetectionSetting {
        if (failReads) throw IllegalStateException("read failed")
        return DetectionSetting(preset, unknownStoredValue)
    }

    override suspend fun save(preset: DetectionPreset) {
        if (failWrites) throw IllegalStateException("write failed")
        saved += preset
        this.preset = preset
        unknownStoredValue = false
    }
}
