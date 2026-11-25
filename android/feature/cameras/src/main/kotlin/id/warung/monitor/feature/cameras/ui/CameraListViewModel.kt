package id.warung.monitor.feature.cameras.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.model.Redaction
import id.warung.monitor.feature.cameras.data.CameraRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the camera list, the add/edit draft, and deletion.
 *
 * The UI never creates a [CameraId]; this ViewModel does, once per camera, so a
 * label edit can never orphan an existing table map.
 */
@HiltViewModel
class CameraListViewModel @Inject constructor(
    private val repository: CameraRepository,
    private val idFactory: CameraIdFactory,
) : ViewModel() {

    private val clock: () -> Long = System::currentTimeMillis

    private val _state = MutableStateFlow(CameraListState.Initial)
    val state: StateFlow<CameraListState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { cameras ->
                _state.update { it.copy(cameras = cameras, isLoading = false) }
            }
        }
    }

    fun startAdd() {
        _state.update { it.copy(editing = CameraEditState.New) }
    }

    fun startEdit(cameraId: CameraId) {
        val profile = _state.value.cameras.firstOrNull { it.id == cameraId } ?: return
        _state.update { it.copy(editing = CameraEditState.from(profile)) }
    }

    fun dismissEditor() {
        _state.update { it.copy(editing = null) }
    }

    fun updateLabel(label: String) = updateEditor { it.copy(label = label.take(CameraLabel.MAX_LENGTH)) }

    fun updateSourceType(sourceType: CameraSourceType) =
        updateEditor { it.copy(sourceType = sourceType, error = null) }

    fun updateHost(host: String) = updateEditor { it.copy(host = host.trim(), error = null) }

    fun updatePort(port: String) =
        updateEditor { it.copy(port = port.filter(Char::isDigit).take(5), error = null) }

    fun updatePath(path: String) = updateEditor { it.copy(streamPath = path.trim(), error = null) }

    fun updateUsername(username: String) = updateEditor { it.copy(username = username.trim(), error = null) }

    /** Validates the draft and persists it; the list updates through Room. */
    fun saveEditor() {
        val draft = _state.value.editing ?: return
        val existing = draft.cameraId?.let { id -> _state.value.cameras.firstOrNull { it.id == id } }
        val profile = runCatching {
            draft.toProfile(id = draft.cameraId ?: idFactory.newId(), existing = existing)
        }.getOrElse { error ->
            updateEditor { it.copy(error = Redaction.message(error.message)) }
            return
        }
        viewModelScope.launch {
            runCatching { repository.upsert(profile) }.onFailure { error ->
                updateEditor { it.copy(error = "Gagal menyimpan: ${Redaction.message(error.message)}") }
            }.onSuccess {
                _state.update {
                    it.copy(editing = null, message = "Kamera ${profile.label.value} tersimpan")
                }
            }
        }
    }

    fun requestDelete(cameraId: CameraId) {
        val profile = _state.value.cameras.firstOrNull { it.id == cameraId } ?: return
        _state.update { it.copy(pendingDelete = profile) }
    }

    fun dismissDelete() {
        _state.update { it.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val profile = _state.value.pendingDelete ?: return
        viewModelScope.launch {
            runCatching { repository.delete(profile.id) }.onFailure { error ->
                _state.update {
                    it.copy(
                        pendingDelete = null,
                        message = "Gagal menghapus: ${Redaction.message(error.message)}",
                    )
                }
            }.onSuccess {
                _state.update {
                    it.copy(pendingDelete = null, message = "Kamera ${profile.label.value} dihapus")
                }
            }
        }
    }

    fun markOpened(cameraId: CameraId) {
        viewModelScope.launch { repository.markOpened(cameraId, clock()) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun updateEditor(transform: (CameraEditState) -> CameraEditState) {
        _state.update { current ->
            current.copy(editing = current.editing?.let(transform))
        }
    }
}

/** Seam so JVM tests produce deterministic camera ids. */
fun interface CameraIdFactory {
    fun newId(): CameraId
}

private fun CameraEditState.toProfile(
    id: CameraId,
    existing: CameraProfile?,
): CameraProfile {
    val label = CameraLabel(label)
    val source = when (sourceType) {
        CameraSourceType.Demo -> CameraSource.Demo
        CameraSourceType.Rtsp -> CameraSource.Rtsp(
            host = host,
            port = port.toIntOrNull() ?: throw IllegalArgumentException("Port tidak valid"),
            path = streamPath,
            username = username.ifBlank { null },
        )
    }
    return CameraProfile(
        id = id,
        label = label,
        source = source,
        createdAt = existing?.createdAt ?: 0L,
        updatedAt = 0L,
        lastOpenedAt = existing?.lastOpenedAt,
    )
}