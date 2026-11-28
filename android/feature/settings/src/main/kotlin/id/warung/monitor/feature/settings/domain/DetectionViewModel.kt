package id.warung.monitor.feature.settings.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.warung.monitor.core.model.DetectionPreset
import id.warung.monitor.core.sync.DetectionSettings
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the detection preset choice.
 *
 * The screen only stores the value; Rust validates and applies it at the next
 * `Engine.start`, so saving never interrupts a running stream. A read failure
 * still leaves a usable form: the default preset is shown and saving works.
 */
@HiltViewModel
class DetectionViewModel @Inject constructor(
    private val settings: DetectionSettings,
) : ViewModel() {

    private val _state = MutableStateFlow(DetectionState())
    val state: StateFlow<DetectionState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun select(preset: DetectionPreset) {
        if (_state.value.saving) return
        _state.update { it.copy(preset = preset, error = null, message = null) }
    }

    fun save(onSaved: () -> Unit = {}) {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                settings.save(current.preset)
                _state.update {
                    it.copy(
                        saving = false,
                        savedPreset = current.preset,
                        unknownStoredValue = false,
                        message = "Deteksi diatur ke ${current.preset.label}",
                    )
                }
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.update {
                    it.copy(saving = false, error = "Gagal menyimpan pilihan deteksi")
                }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun load() {
        try {
            val setting = settings.load()
            _state.update {
                it.copy(
                    loading = false,
                    preset = setting.preset,
                    savedPreset = setting.preset,
                    unknownStoredValue = setting.unknownStoredValue,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // A failed read must not lock the form; the default is shown and
            // saving remains available.
            _state.update { it.copy(loading = false) }
        }
    }
}
