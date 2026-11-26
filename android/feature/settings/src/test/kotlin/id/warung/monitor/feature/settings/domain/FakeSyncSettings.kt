package id.warung.monitor.feature.settings.domain

import id.warung.monitor.core.sync.SyncEndpoint
import id.warung.monitor.core.sync.SyncSettings

/** In-memory settings; records writes so tests can assert what reached storage. */
class FakeSyncSettings(
    var baseUrl: String? = null,
    private var token: String? = null,
    var failWrites: Boolean = false,
    var failReads: Boolean = false,
) : SyncSettings {

    val savedUrls = mutableListOf<String>()
    val savedTokens = mutableListOf<String?>()

    override suspend fun endpoint(): SyncEndpoint? =
        baseUrl?.let { SyncEndpoint(it, token) }

    override suspend fun configuredBaseUrl(): String? {
        if (failReads) throw IllegalStateException("read failed")
        return baseUrl
    }

    override suspend fun hasToken(): Boolean {
        if (failReads) throw IllegalStateException("read failed")
        return !token.isNullOrBlank()
    }

    override suspend fun saveBaseUrl(baseUrl: String) {
        if (failWrites) throw IllegalStateException("write failed")
        SyncEndpoint(baseUrl, null)
        savedUrls += baseUrl
        this.baseUrl = baseUrl
    }

    override suspend fun saveToken(token: String?) {
        if (failWrites) throw IllegalStateException("write failed")
        savedTokens += token
        this.token = token
    }

    override suspend fun clear() {
        baseUrl = null
        token = null
    }
}
