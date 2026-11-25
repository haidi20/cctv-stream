package id.warung.monitor.feature.tables.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.warung.monitor.core.model.MappingFrame
import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.Redaction
import id.warung.monitor.core.model.TableConfigSet
import id.warung.monitor.core.model.TableId
import id.warung.monitor.feature.tables.data.StoredTableConfig
import id.warung.monitor.feature.tables.data.TableConfigRepository
import id.warung.monitor.feature.tables.domain.PolygonEditResult
import id.warung.monitor.feature.tables.domain.PolygonEditor
import id.warung.monitor.feature.tables.domain.TableDraft
import id.warung.monitor.feature.tables.engine.TableApplyResult
import id.warung.monitor.feature.tables.engine.TableConfigSink
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the mapping draft, undo history, persistence, and the engine hand-off.
 *
 * Ordering is deliberate: validate locally, save the revision to Room, and only
 * then call `setTables`. An engine that is missing or rejects the JSON must not
 * cost the user their map.
 */
@HiltViewModel
class TableMappingViewModel @Inject constructor(
    private val repository: TableConfigRepository,
    private val sink: TableConfigSink,
) : ViewModel() {

    private val _state = MutableStateFlow(TableMappingState.Initial)
    val state: StateFlow<TableMappingState> = _state.asStateFlow()

    private var undoStack: ArrayDeque<Snapshot> = ArrayDeque()
    private var redoStack: ArrayDeque<Snapshot> = ArrayDeque()

    private var cameraId: String? = null
    private var cameraLabel: String? = null

    private data class Snapshot(
        val tables: List<TableDraft>,
        val selectedTableId: TableId?,
        val selectedVertex: Int?,
        val drawingTableId: TableId?,
    )

    /**
     * Binds this screen to one camera.
     *
     * Called once by the route with the id the user opened. Rebinding discards
     * the previous draft so camera A's unfinished polygon can never be saved to
     * camera B.
     */
    fun bindCamera(cameraId: String, cameraLabel: String) {
        val nextId = cameraId.trim()
        if (nextId.isEmpty()) {
            load()
            return
        }
        if (this.cameraId == nextId && this.cameraLabel == cameraLabel) return
        this.cameraId = nextId
        this.cameraLabel = cameraLabel
        undoStack = ArrayDeque()
        redoStack = ArrayDeque()
        _state.update {
            it.copy(
                cameraId = nextId,
                cameraLabel = cameraLabel,
                tables = emptyList(),
                configuredFrame = null,
                lastSavedRevision = null,
                engineResult = null,
                selectedTableId = null,
                selectedVertex = null,
                drawingTableId = null,
                canUndo = false,
                canRedo = false,
                snapshot = null,
            )
        }
        load()
    }

    fun load() {
        val activeCamera = cameraId ?: run {
            _state.update { it.copy(message = "Pilih kamera sebelum memuat peta") }
            return
        }
        viewModelScope.launch {
            val stored = runCatching { repository.active(activeCamera) }.getOrElse { error ->
                _state.update { it.copy(message = "Gagal membaca peta: ${Redaction.message(error.message)}") }
                null
            }
            if (cameraId == activeCamera) applyStored(stored)
        }
    }

    /** Freezes the current stream frame as the canvas backdrop. */
    fun setSnapshot(bitmap: ImageBitmap) {
        _state.update { it.copy(snapshot = bitmap) }
    }

    /** Drops the frozen frame and returns to the live preview. */
    fun clearSnapshot() {
        _state.update { it.copy(snapshot = null) }
    }

    /** Updates the live frame; the banner only depends on the saved frame. */
    fun setLiveFrame(width: Int, height: Int, aspectRatio: String) {
        runCatching { MappingFrame(width, height, aspectRatio) }.onSuccess { frame ->
            _state.update { it.copy(frame = frame) }
        }
    }

    fun addTable() {
        val current = _state.value
        if (current.drawingTableId != null) {
            finishDrawing()
            return
        }
        pushHistory(current)
        val id = PolygonEditor.nextId(current.tables.map { it.id })
        val draft = TableDraft(
            id = id,
            label = "Meja ${id.value.removePrefix("meja-")}",
            points = emptyList(),
        )
        _state.update {
            it.copy(
                tables = it.tables + draft,
                selectedTableId = id,
                selectedVertex = null,
                drawingTableId = id,
                message = "Ketuk bingkai untuk menambah titik (minimal 3)",
            )
        }
    }

    fun selectTable(id: TableId?) {
        _state.update { current ->
            if (id == null || current.tables.none { it.id == id }) {
                current.copy(selectedTableId = null, selectedVertex = null)
            } else {
                current.copy(selectedTableId = id, selectedVertex = null)
            }
        }
    }

    fun selectVertex(index: Int?) {
        _state.update { it.copy(selectedVertex = index) }
    }

    fun addVertex(point: NormalizedPoint) {
        val current = _state.value
        val target = current.drawingTableId ?: current.selectedTableId ?: return addTable()
        val draft = current.tables.firstOrNull { it.id == target } ?: return
        when (val result = PolygonEditor.addVertex(draft.points, point)) {
            is PolygonEditResult.Success -> {
                pushHistory(current)
                replaceDraft(draft.copy(points = result.points))
            }
            is PolygonEditResult.Rejected -> showMessage(result.message)
        }
    }

    fun moveVertex(tableId: TableId, index: Int, point: NormalizedPoint) {
        val current = _state.value
        val draft = current.tables.firstOrNull { it.id == tableId } ?: return
        when (val result = PolygonEditor.moveVertex(draft.points, index, point)) {
            is PolygonEditResult.Success -> {
                pushHistory(current)
                replaceDraft(draft.copy(points = result.points))
            }
            is PolygonEditResult.Rejected -> showMessage(result.message)
        }
    }

    fun removeVertex(tableId: TableId, index: Int) {
        val current = _state.value
        val draft = current.tables.firstOrNull { it.id == tableId } ?: return
        when (val result = PolygonEditor.removeVertex(draft.points, index)) {
            is PolygonEditResult.Success -> {
                pushHistory(current)
                replaceDraft(draft.copy(points = result.points))
            }
            is PolygonEditResult.Rejected -> showMessage(result.message)
        }
    }

    fun updateLabel(tableId: TableId, label: String) {
        val current = _state.value
        val draft = current.tables.firstOrNull { it.id == tableId } ?: return
        pushHistory(current)
        replaceDraft(draft.copy(label = label.take(40)))
    }

    fun deleteTable(tableId: TableId) {
        val current = _state.value
        if (current.tables.none { it.id == tableId }) return
        pushHistory(current)
        _state.update {
            it.copy(
                tables = it.tables.filterNot { table -> table.id == tableId },
                selectedTableId = it.selectedTableId.takeIf { selected -> selected != tableId },
                selectedVertex = null,
                drawingTableId = it.drawingTableId.takeIf { drawing -> drawing != tableId },
            )
        }
    }

    fun finishDrawing() {
        val current = _state.value
        val drawingId = current.drawingTableId ?: return
        val draft = current.tables.firstOrNull { it.id == drawingId } ?: return
        if (draft.isValid) {
            _state.update {
                it.copy(drawingTableId = null, message = "${draft.displayLabel} siap disimpan")
            }
        } else {
            showMessage(draft.problemText ?: "Poligon minimal 3 titik")
        }
    }

    fun cancelDrawing() {
        val current = _state.value
        val drawingId = current.drawingTableId ?: return
        val draft = current.tables.firstOrNull { it.id == drawingId } ?: return
        pushHistory(current)
        if (draft.points.isEmpty()) {
            _state.update {
                it.copy(
                    tables = it.tables.filterNot { table -> table.id == drawingId },
                    selectedTableId = null,
                    selectedVertex = null,
                    drawingTableId = null,
                    message = null,
                )
            }
        } else {
            _state.update { it.copy(drawingTableId = null, message = null) }
        }
    }

    fun resetMap() {
        val current = _state.value
        if (current.tables.isEmpty()) return
        pushHistory(current)
        _state.update {
            it.copy(
                tables = emptyList(),
                selectedTableId = null,
                selectedVertex = null,
                drawingTableId = null,
                message = "Peta dikosongkan. Simpan untuk menerapkan.",
            )
        }
    }

    fun undo() {
        val current = _state.value
        val snapshot = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snapshot(current.tables, current.selectedTableId, current.selectedVertex, current.drawingTableId))
        restore(snapshot)
    }

    fun redo() {
        val current = _state.value
        val snapshot = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snapshot(current.tables, current.selectedTableId, current.selectedVertex, current.drawingTableId))
        restore(snapshot)
    }

    fun save() {
        val current = _state.value
        if (current.isSaving) return
        if (current.tables.isEmpty()) {
            showMessage("Tambahkan minimal satu meja sebelum menyimpan")
            return
        }
        val invalid = current.tables.firstOrNull { !it.isValid } ?: run {
            persistAndApply(current)
            return
        }
        _state.update {
            it.copy(selectedTableId = invalid.id, message = "${invalid.displayLabel}: ${invalid.problemText}")
        }
    }

    private fun persistAndApply(current: TableMappingState) {
        val activeCamera = cameraId ?: run {
            showMessage("Pilih kamera sebelum menyimpan")
            return
        }
        val configs = current.tables.mapNotNull { it.toConfig() }
        val config = TableConfigSet(current.frame, configs)
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val stored = runCatching { repository.save(activeCamera, config) }.getOrElse { error ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        message = "Gagal menyimpan peta: ${Redaction.message(error.message)}",
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    configuredFrame = config.frame,
                    lastSavedRevision = stored.revision,
                    isSaving = false,
                    message = "Peta tersimpan sebagai revisi ${stored.revision}",
                )
            }
            if (cameraId == activeCamera) applyToEngine(activeCamera, config, stored)
        }
    }

    private suspend fun applyToEngine(
        activeCamera: String,
        config: TableConfigSet,
        stored: StoredTableConfig,
    ) {
        val json = runCatching { id.warung.monitor.core.model.TableConfigJson.encodeOrThrow(config) }
            .getOrElse { error ->
                _state.update {
                    it.copy(engineResult = TableApplyResult.Unavailable(error.message.orEmpty()))
                }
                return
            }
        val result = runCatching { sink.apply(activeCamera, json) }
            .getOrElse { error -> TableApplyResult.Unavailable(error.message.orEmpty()) }
        val message = when (result) {
            is TableApplyResult.Applied -> "Peta diterapkan ke engine"
            is TableApplyResult.Rejected -> "Engine menolak peta: ${Redaction.message(result.reason)}"
            is TableApplyResult.Unavailable -> "Peta tersimpan lokal; engine belum aktif"
            is TableApplyResult.WrongCamera -> "Peta tersimpan lokal; kamera ini bukan sesi aktif"
        }
        _state.update {
            it.copy(
                engineResult = result,
                message = message,
                lastSavedRevision = stored.revision,
            )
        }
    }

    /** Retries the engine with the last saved revision, when one exists. */
    fun retryEngine() {
        val activeCamera = cameraId ?: run {
            showMessage("Pilih kamera sebelum mencoba engine")
            return
        }
        viewModelScope.launch {
            val stored = runCatching { repository.active(activeCamera) }.getOrNull() ?: run {
                showMessage("Belum ada peta tersimpan")
                return@launch
            }
            applyToEngine(activeCamera, stored.config, stored)
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun applyStored(stored: StoredTableConfig?) {
        val tables = stored?.config?.tables.orEmpty().map { table ->
            TableDraft(table.id, table.label.value, table.polygon.points)
        }
        _state.update {
            it.copy(
                cameraId = cameraId.orEmpty(),
                cameraLabel = cameraLabel.orEmpty(),
                tables = tables,
                configuredFrame = stored?.config?.frame,
                lastSavedRevision = stored?.revision,
                selectedTableId = null,
                selectedVertex = null,
                drawingTableId = null,
                canUndo = false,
                canRedo = false,
            )
        }
        undoStack = ArrayDeque()
        redoStack = ArrayDeque()
    }

    private fun replaceDraft(draft: TableDraft) {
        _state.update { current ->
            current.copy(
                tables = current.tables.map { table -> if (table.id == draft.id) draft else table },
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
        }
    }

    private fun restore(snapshot: Snapshot) {
        _state.update {
            it.copy(
                tables = snapshot.tables,
                selectedTableId = snapshot.selectedTableId,
                selectedVertex = snapshot.selectedVertex,
                drawingTableId = snapshot.drawingTableId,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
        }
    }

    private fun pushHistory(current: TableMappingState) {
        undoStack.addLast(
            Snapshot(current.tables, current.selectedTableId, current.selectedVertex, current.drawingTableId),
        )
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        _state.update { it.copy(canUndo = true, canRedo = false) }
    }

    private fun showMessage(text: String) {
        _state.update { it.copy(message = Redaction.message(text)) }
    }

    companion object {
        const val MAX_HISTORY: Int = 50
    }
}