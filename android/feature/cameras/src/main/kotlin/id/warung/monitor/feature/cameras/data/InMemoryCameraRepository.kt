package id.warung.monitor.feature.cameras.data

import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Deterministic repository for JVM tests and previews. */
class InMemoryCameraRepository(
    initial: List<CameraProfile> = emptyList(),
) : CameraRepository {

    private val profiles = linkedMapOf<String, CameraProfile>()
    private val state = MutableStateFlow<List<CameraProfile>>(emptyList())

    init {
        initial.forEach { profiles[it.id.value] = it }
        publish()
    }

    override fun observeAll(): Flow<List<CameraProfile>> = state.asStateFlow()

    override suspend fun all(): List<CameraProfile> = sorted()

    override suspend fun find(id: CameraId): CameraProfile? = profiles[id.value]

    override suspend fun upsert(profile: CameraProfile) {
        profiles[profile.id.value] = profile
        publish()
    }

    override suspend fun markOpened(id: CameraId, at: Long) {
        val current = profiles[id.value] ?: return
        profiles[id.value] = current.copy(lastOpenedAt = at)
        publish()
    }

    override suspend fun delete(id: CameraId) {
        profiles.remove(id.value)
        publish()
    }

    override suspend fun setMonitoringEnabled(id: CameraId, enabled: Boolean) {
        val current = profiles[id.value] ?: return
        profiles[id.value] = current.copy(monitoringEnabled = enabled)
        publish()
    }

    /** Test helper: how many profiles a camera deletion would have removed. */
    fun size(): Int = profiles.size

    private fun publish() {
        state.value = sorted()
    }

    private fun sorted(): List<CameraProfile> =
        profiles.values.sortedWith(compareBy({ it.label.value.lowercase() }, { it.id.value }))
}

