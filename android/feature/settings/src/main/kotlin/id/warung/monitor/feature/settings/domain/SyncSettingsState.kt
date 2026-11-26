package id.warung.monitor.feature.settings.domain

/**
 * UI state for the optional VPS connection.
 *
 * The token is never part of this state: the screen only knows whether one is
 * stored. Showing a saved token would put it in the composition, in
 * screenshots, and in any crash report that captures the view tree.
 */
data class SyncSettingsState(
    val loading: Boolean = true,
    val baseUrl: String = "",
    val tokenDraft: String = "",
    val hasSavedToken: Boolean = false,
    val saving: Boolean = false,
    /** Validation or save failure; never contains the URL or token value. */
    val error: String? = null,
    val message: String? = null,
) {
    val canSave: Boolean
        get() = !saving && baseUrl.isNotBlank()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank()

    /** True when the user typed something into the token field this session. */
    val hasTokenDraft: Boolean
        get() = tokenDraft.isNotBlank()
}
