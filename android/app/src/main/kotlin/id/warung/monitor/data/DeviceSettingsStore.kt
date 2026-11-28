package id.warung.monitor.data

import id.warung.monitor.core.database.DeviceSettingsDao
import id.warung.monitor.core.database.DeviceSettingsEntity
import id.warung.monitor.engine.EngineTuning
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers what this phone measured so the next session starts tuned.
 *
 * The store is the only writer of `device_settings`. It is deliberately thin:
 * the decision itself lives in [EngineTuning], which is pure and unit-tested,
 * while this class only moves one row in and out of Room.
 *
 * A row is a *record*, never an authority. If the phone was hot during the last
 * session its p95 is high and the stored rate is low; the next session re-derives
 * the rate from that p95 against the current profile ceiling, so a cool phone
 * recovers on its own instead of staying capped by one bad measurement.
 */
interface DeviceSettingsStore {
    /** The last tuning this device ran with, or null before the first session. */
    suspend fun load(): StoredDeviceSettings?

    /** Overwrites the single row with what the session just ran with. */
    suspend fun save(settings: StoredDeviceSettings)
}

/** One measured tuning, as persisted in `device_settings`. */
data class StoredDeviceSettings(
    val tuning: EngineTuning,
    /** p95 inference latency Rust reported, or null when nothing was sampled. */
    val p95InferenceMs: Long?,
)

@Singleton
class RoomDeviceSettingsStore(
    private val dao: DeviceSettingsDao,
    private val clock: () -> Long,
) : DeviceSettingsStore {

    /** Production wiring; the clock is a seam for tests, not a dependency. */
    @Inject
    constructor(dao: DeviceSettingsDao) : this(dao, System::currentTimeMillis)

    override suspend fun load(): StoredDeviceSettings? {
        val row = dao.settings() ?: return null
        return StoredDeviceSettings(
            tuning = EngineTuning(
                inferenceFps = row.inferenceFps,
                intraThreads = row.intraThreads.toUInt(),
            ),
            p95InferenceMs = row.p95InferenceMs,
        )
    }

    override suspend fun save(settings: StoredDeviceSettings) {
        dao.save(
            DeviceSettingsEntity(
                inferenceFps = settings.tuning.inferenceFps,
                intraThreads = settings.tuning.intraThreads.toInt(),
                p95InferenceMs = settings.p95InferenceMs,
                updatedAt = clock(),
            ),
        )
    }
}
