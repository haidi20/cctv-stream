package id.warung.monitor.feature.tables.ui

import androidx.compose.ui.graphics.ImageBitmap
import id.warung.monitor.core.model.MappingFrame
import id.warung.monitor.core.model.TableId
import id.warung.monitor.feature.tables.domain.TableDraft
import id.warung.monitor.feature.tables.engine.TableApplyResult

/** Everything the mapping screen draws. The UI never computes occupancy. */
data class TableMappingState(
    val cameraId: String,
    val cameraLabel: String,
    val frame: MappingFrame,
    val configuredFrame: MappingFrame?,
    val tables: List<TableDraft>,
    val selectedTableId: TableId?,
    val selectedVertex: Int?,
    val drawingTableId: TableId?,
    val isSaving: Boolean,
    val lastSavedRevision: Long?,
    val engineResult: TableApplyResult?,
    val message: String?,
    val canUndo: Boolean,
    val canRedo: Boolean,
    /** Frozen frame shown under the polygons; never persisted. */
    val snapshot: ImageBitmap? = null,
) {
    val selectedTable: TableDraft? get() = tables.firstOrNull { it.id == selectedTableId }

    val drawingTable: TableDraft? get() = tables.firstOrNull { it.id == drawingTableId }

    val readyCount: Int get() = tables.count { it.isValid }

    val allValid: Boolean get() = tables.isNotEmpty() && tables.all { it.isValid }

    val canSave: Boolean get() = allValid && !isSaving

    /** True when the saved map was drawn for a different aspect ratio than the live frame. */
    val needsRecalibration: Boolean
        get() = configuredFrame != null &&
            !configuredFrame.matchesAspectRatio(frame.width, frame.height)

    val statusText: String
        get() = if (tables.isEmpty()) {
            "Belum ada meja"
        } else {
            "$readyCount/${tables.size} meja siap"
        }

    companion object {
        val Initial: TableMappingState = TableMappingState(
            cameraId = "",
            cameraLabel = "",
            frame = MappingFrame(1280, 720, "16:9"),
            configuredFrame = null,
            tables = emptyList(),
            selectedTableId = null,
            selectedVertex = null,
            drawingTableId = null,
            isSaving = false,
            lastSavedRevision = null,
            engineResult = null,
            message = null,
            canUndo = false,
            canRedo = false,
            snapshot = null,
        )
    }
}