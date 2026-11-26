package id.warung.monitor.feature.settings.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.warung.monitor.core.sync.SyncSettings
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the optional VPS form.
 *
 * The token field is write-only: it starts empty even when a token is stored,
 * and saving with an empty field keeps the existing token. That avoids putting
 * the secret back into the composition after a rotation or process death.
 */
@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    private val settings: SyncSettings,
) : ViewModel() {

    private val _state = MutableStateFlow(SyncSettingsState())
    val state: StateFlow<SyncSettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun updateBaseUrl(value: String) {
        _state.update {
            it.copy(baseUrl = value.take(SyncUrl.MAX_LENGTH), error = null, message = null)
        }
    }

    fun updateToken(value: String) {
        _state.update { it.copy(tokenDraft = value, error = null, message = null) }
    }

    /**
     * Validates and persists; the URL is stored without a trailing slash.
     *
     * [onSaved] runs only after both writes succeeded, so `app` can restart the
     * worker without guessing whether the form was valid.
     */
    fun save(onSaved: () -> Unit = {}) {
        val current = _state.value
        if (current.saving) return
        when (val result = SyncUrl.validate(current.baseUrl)) {
            is SyncUrl.Result.Invalid -> {
                _state.update { it.copy(error = result.reason) }
            }

            is SyncUrl.Result.Valid -> {
                _state.update { it.copy(saving = true, error = null, message = null) }
                viewModelScope.launch {
                    try {
                        settings.saveBaseUrl(result.normalized)
                        if (current.hasTokenDraft) settings.saveToken(current.tokenDraft)
                        _state.update {
                            it.copy(
                                saving = false,
                                baseUrl = result.normalized,
                                tokenDraft = "",
                                hasSavedToken = it.hasSavedToken || current.hasTokenDraft,
                                message = "Alamat server disimpan",
                            )
                        }
                        onSaved()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        _state.update {
                            it.copy(saving = false, error = "Gagal menyimpan alamat server")
                        }
                    }
                }
            }
        }
    }

    /** Removes the token but keeps the URL, so the user can re-enroll. */
    fun clearToken() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                settings.saveToken(null)
                _state.update {
                    it.copy(saving = false, tokenDraft = "", hasSavedToken = false, message = "Token dihapus")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.update { it.copy(saving = false, error = "Gagal menghapus token") }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun load() {
        try {
            val baseUrl = settings.configuredBaseUrl().orEmpty()
            val hasToken = settings.hasToken()
            _state.update {
                it.copy(loading = false, baseUrl = baseUrl, hasSavedToken = hasToken)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // A failed read must not block the form; saving still works.
            _state.update { it.copy(loading = false) }
        }
    }
}
