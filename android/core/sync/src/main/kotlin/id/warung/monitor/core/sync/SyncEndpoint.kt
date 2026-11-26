package id.warung.monitor.core.sync

/**
 * Server coordinates for `POST /v1/sync` (`docs/server-api.md` section 4).
 *
 * There is no default URL: sync is optional and the user must configure it.
 * [baseUrl] must be HTTPS; a plain-HTTP VPS would leak the device token and the
 * occupancy history, so it is rejected before any request is attempted or the
 * value reaches storage.
 */
data class SyncEndpoint(val baseUrl: String, val token: String?) {

    init {
        require(baseUrl.startsWith("https://")) { "sync base url must use https" }
        require(!token.isNullOrBlank() || token == null) { "token must not be blank" }
    }

    /** Full URL for the sync call; never logged. */
    fun syncUrl(): String = baseUrl.trimEnd('/') + SYNC_PATH

    /** Full URL for `POST /v1/heartbeat`; never logged. */
    fun heartbeatUrl(): String = baseUrl.trimEnd('/') + HEARTBEAT_PATH

    companion object {
        const val SYNC_PATH: String = "/v1/sync"
        const val HEARTBEAT_PATH: String = "/v1/heartbeat"
    }
}
