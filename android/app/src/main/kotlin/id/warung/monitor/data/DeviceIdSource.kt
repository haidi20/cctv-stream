package id.warung.monitor.data

/** Stable per-install id; an interface keeps the sync worker testable. */
interface DeviceIdSource {
    suspend fun deviceId(): String
}

