package id.warung.monitor.feature.cameras.data

import id.warung.monitor.core.model.CameraId

/**
 * Keystore-backed RTSP passwords, keyed by the stable camera id.
 *
 * The interface is deliberately JVM-only: the editor and Home ViewModels
 * need to save/read a secret without depending on Android APIs. Android
 * implementations must encrypt at rest and never return a stored password in
 * `toString()`, logs, or error messages.
 */
interface CameraSecretStore {
    /** Returns a fresh char array the caller owns and must wipe after use. */
    suspend fun read(id: CameraId): CharArray?

    /** Stores a copy of [secret]; the caller still owns and wipes its array. */
    suspend fun save(id: CameraId, secret: CharArray)

    suspend fun delete(id: CameraId)
}

/** Test store that keeps secrets in memory only. */
class InMemoryCameraSecretStore : CameraSecretStore {
    private val secrets = mutableMapOf<String, CharArray>()

    override suspend fun read(id: CameraId): CharArray? =
        secrets[id.value]?.copyOf()

    override suspend fun save(id: CameraId, secret: CharArray) {
        secrets[id.value] = secret.copyOf()
    }

    override suspend fun delete(id: CameraId) {
        secrets.remove(id.value)?.fill('\u0000')
    }
}