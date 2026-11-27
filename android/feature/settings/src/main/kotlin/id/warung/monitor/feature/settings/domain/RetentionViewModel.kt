package id.warung.monitor.feature.settings.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.warung.monitor.core.sync.LocalHistoryStore
import id.warung.monitor.core.sync.RetentionSettings
import id.warung.monitor.core.sync.RetentionWindow
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the retention choice and the storage counters.
 *
 * Saving is deliberately not destructive: `android-sync.md` section 7 requires
 * the change to take effect on the next prune pass so a large delete never
 * blocks the screen. [onSaved] lets `app` reschedule the worker.
 */
@HiltViewModel
class RetentionViewModel @Inject constructor(
    private val settings: RetentionSettings,
    private val history: LocalHistoryStore,
) : ViewModel() {

    private val _state = MutableStateFlow(RetentionState())
    val state: StateFlow<RetentionState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun select(window: RetentionWindow) {
        if (_state.value.saving) return
        _state.update { it.copy(window = window, error = null, message = null) }
    }

    fun save(onSaved: () -> Unit = {}) {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                settings.save(current.window)
                // Re-read the counters after the write so the screen never
                // shows a value that disagrees with what was just stored.
                val stats = readStats()
                _state.update {
                    it.copy(
                        saving = false,
                        savedWindow = current.window,
                        rows = stats?.rows ?: it.rows,
                        databaseBytes = stats?.databaseBytes ?: it.databaseBytes,
                        rowLimit = stats?.rowLimit ?: it.rowLimit,
                        message = "Masa simpan diatur ke ${current.window.days} hari",
                    )
                }
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.update {
                    it.copy(saving = false, error = "Gagal menyimpan masa simpan")
                }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun load() {
        try {
            val window = settings.window()
            val stats = readStats()
            _state.update {
                it.copy(
                    loading = false,
                    window = window,
                    savedWindow = window,
                    rows = stats?.rows ?: it.rows,
                    databaseBytes = stats?.databaseBytes ?: it.databaseBytes,
                    rowLimit = stats?.rowLimit ?: it.rowLimit,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // An unreadable counter must not lock the form: the window itself
            // still loads, and saving remains available.
            _state.update { it.copy(loading = false) }
        }
    }

    /** Counters are advisory; a failure keeps the previous numbers. */
    private suspend fun readStats() = try {
        history.stats()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }
}
