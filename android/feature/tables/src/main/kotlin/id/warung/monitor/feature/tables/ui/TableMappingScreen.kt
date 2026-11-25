package id.warung.monitor.feature.tables.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import id.warung.monitor.core.model.TableId
import id.warung.monitor.feature.tables.domain.TableDraft
import id.warung.monitor.feature.tables.engine.TableApplyResult

/**
 * Stateless mapping screen: every value comes from [state] and every intent
 * leaves through a callback, which keeps Compose UI tests free of ViewModel.
 *
 * Width, not orientation, chooses the layout: below 600dp the panel stacks under
 * the preview; at or above it the panel sits on the right (max 420dp).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableMappingScreen(
    state: TableMappingState,
    onBack: () -> Unit,
    onAddTable: () -> Unit,
    onFinishDrawing: () -> Unit,
    onCancelDrawing: () -> Unit,
    onSelectTable: (TableId?) -> Unit,
    onSelectVertex: (Int?) -> Unit,
    onAddPoint: (id.warung.monitor.core.model.NormalizedPoint) -> Unit,
    onMoveVertex: (TableId, Int, id.warung.monitor.core.model.NormalizedPoint) -> Unit,
    onRemoveVertex: (TableId, Int) -> Unit,
    onRemoveTable: (TableId) -> Unit,
    onLabelChange: (TableId, String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onResetMap: () -> Unit,
    onRetryEngine: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    preview: (@Composable () -> Unit)? = null,
    onClearSnapshot: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissMessage()
    }

    Scaffold(
        modifier = modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (state.cameraLabel.isBlank()) "Peta Meja" else state.cameraLabel)
                        Text(
                            text = state.statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    Button(
                        onClick = onSave,
                        enabled = state.canSave,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("save-map"),
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Simpan")
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val wide = maxWidth >= 600.dp
            if (wide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    PreviewPane(
                        state = state,
                        onAddPoint = onAddPoint,
                        onSelectTable = onSelectTable,
                        onSelectVertex = onSelectVertex,
                        onMoveVertex = onMoveVertex,
                        onRemoveVertex = onRemoveVertex,
                        modifier = Modifier.weight(1f),
                        onResetMap = onResetMap,
                        preview = preview,
                        onClearSnapshot = onClearSnapshot,
                    )
                    Surface(
                        modifier = Modifier
                            .width(420.dp)
                            .fillMaxSize(),
                        tonalElevation = 2.dp,
                    ) {
                        ControlPanel(
                            state = state,
                            onAddTable = onAddTable,
                            onFinishDrawing = onFinishDrawing,
                            onCancelDrawing = onCancelDrawing,
                            onSelectTable = { tableId -> onSelectTable(tableId) },
                            onRemoveTable = onRemoveTable,
                            onLabelChange = onLabelChange,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            onResetMap = onResetMap,
                            onRetryEngine = onRetryEngine,
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    PreviewPane(
                        state = state,
                        onAddPoint = onAddPoint,
                        onSelectTable = onSelectTable,
                        onSelectVertex = onSelectVertex,
                        onMoveVertex = onMoveVertex,
                        onRemoveVertex = onRemoveVertex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.55f),
                        onResetMap = onResetMap,
                        preview = preview,
                        onClearSnapshot = onClearSnapshot,
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.45f),
                        tonalElevation = 2.dp,
                    ) {
                        ControlPanel(
                            state = state,
                            onAddTable = onAddTable,
                            onFinishDrawing = onFinishDrawing,
                            onCancelDrawing = onCancelDrawing,
                            onSelectTable = { tableId -> onSelectTable(tableId) },
                            onRemoveTable = onRemoveTable,
                            onLabelChange = onLabelChange,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            onResetMap = onResetMap,
                            onRetryEngine = onRetryEngine,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewPane(
    state: TableMappingState,
    onAddPoint: (id.warung.monitor.core.model.NormalizedPoint) -> Unit,
    onSelectTable: (TableId?) -> Unit,
    onSelectVertex: (Int?) -> Unit,
    onMoveVertex: (TableId, Int, id.warung.monitor.core.model.NormalizedPoint) -> Unit,
    onRemoveVertex: (TableId, Int) -> Unit,
    modifier: Modifier = Modifier,
    onResetMap: () -> Unit,
    preview: (@Composable () -> Unit)? = null,
    onClearSnapshot: () -> Unit = {},
) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val snapshot = state.snapshot
            if (snapshot != null) {
                MappingCanvas(
                    frame = state.frame,
                    tables = state.tables,
                    selectedTableId = state.selectedTableId,
                    selectedVertex = state.selectedVertex,
                    drawingTableId = state.drawingTableId,
                    modifier = Modifier.fillMaxSize(),
                    backdrop = snapshot,
                    onAddPoint = onAddPoint,
                    onSelectTable = onSelectTable,
                    onSelectVertex = { tableId, index ->
                        onSelectTable(tableId)
                        onSelectVertex(index)
                    },
                    onMoveVertex = onMoveVertex,
                    onRemoveVertex = onRemoveVertex,
                )
                TextButton(
                    onClick = onClearSnapshot,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .testTag("unfreeze-frame"),
                ) {
                    Text("Ambil ulang")
                }
            } else if (preview != null) {
                preview()
            } else {
                MappingCanvas(
                    frame = state.frame,
                    tables = state.tables,
                    selectedTableId = state.selectedTableId,
                    selectedVertex = state.selectedVertex,
                    drawingTableId = state.drawingTableId,
                    modifier = Modifier.fillMaxSize(),
                    onAddPoint = onAddPoint,
                    onSelectTable = onSelectTable,
                    onSelectVertex = { tableId, index ->
                        onSelectTable(tableId)
                        onSelectVertex(index)
                    },
                    onMoveVertex = onMoveVertex,
                    onRemoveVertex = onRemoveVertex,
                )
            }
        }
        if (state.needsRecalibration) {
            RecalibrationBanner(
                configured = state.configuredFrame,
                live = state.frame,
                onResetMap = onResetMap,
            )
        }
    }
}

@Composable
private fun RecalibrationBanner(
    configured: id.warung.monitor.core.model.MappingFrame?,
    live: id.warung.monitor.core.model.MappingFrame,
    onResetMap: () -> Unit,
) {
    if (configured == null) return
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Peta dibuat untuk ${configured.aspectRatio}, video sekarang " +
                    "${live.aspectRatio}. Gambar ulang peta.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResetMap) {
                Text("Mulai ulang")
            }
        }
    }
}

@Composable
private fun ControlPanel(
    state: TableMappingState,
    onAddTable: () -> Unit,
    onFinishDrawing: () -> Unit,
    onCancelDrawing: () -> Unit,
    onSelectTable: (TableId?) -> Unit,
    onRemoveTable: (TableId) -> Unit,
    onLabelChange: (TableId, String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onResetMap: () -> Unit,
    onRetryEngine: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("mapping-panel"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = if (state.drawingTableId != null) onFinishDrawing else onAddTable,
                    modifier = Modifier.testTag("add-table"),
                ) {
                    Icon(
                        if (state.drawingTableId != null) Icons.Filled.Check else Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.drawingTableId != null) "Selesai" else "Tambah Meja")
                }
                if (state.drawingTableId != null) {
                    TextButton(onClick = onCancelDrawing) {
                        Text("Batal")
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onUndo, enabled = state.canUndo) {
                    Text("Urungkan")
                }
                TextButton(onClick = onRedo, enabled = state.canRedo) {
                    Text("Ulangi")
                }
            }
        }

        if (state.needsRecalibration) {
            item {
                AssistChip(
                    onClick = onRetryEngine,
                    label = { Text("Rasio berubah: mulailah ulang peta") },
                    leadingIcon = {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
        }

        item {
            EngineStatusRow(
                result = state.engineResult,
                revision = state.lastSavedRevision,
                onRetry = onRetryEngine,
            )
        }

        items(items = state.tables, key = { it.id.value }) { table ->
            TableCard(
                table = table,
                selected = table.id == state.selectedTableId,
                onSelect = { onSelectTable(table.id) },
                onLabelChange = { onLabelChange(table.id, it) },
                onRemove = { onRemoveTable(table.id) },
            )
        }

        if (state.tables.isNotEmpty()) {
            item {
                HorizontalDivider()
                TextButton(onClick = onResetMap) {
                    Text("Kosongkan peta")
                }
            }
        }
    }
}

@Composable
private fun EngineStatusRow(
    result: TableApplyResult?,
    revision: Long?,
    onRetry: () -> Unit,
) {
    val (text, color) = when (result) {
        is TableApplyResult.Applied -> "Engine aktif" to MaterialTheme.colorScheme.primary
        is TableApplyResult.Rejected -> "Engine menolak peta" to MaterialTheme.colorScheme.error
        is TableApplyResult.Unavailable -> "Disimpan lokal, engine belum aktif" to
            MaterialTheme.colorScheme.onSurfaceVariant
        is TableApplyResult.WrongCamera -> "Disimpan lokal, kamera ini bukan sesi aktif" to
            MaterialTheme.colorScheme.error
        null -> "Belum disimpan" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (revision != null) "$text (revisi $revision)" else text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.weight(1f),
        )
        if (result !is TableApplyResult.Applied) {
            TextButton(onClick = onRetry) {
                Text("Coba lagi")
            }
        }
    }
}

@Composable
private fun TableCard(
    table: TableDraft,
    selected: Boolean,
    onSelect: () -> Unit,
    onLabelChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${table.displayLabel}, ${table.points.size} titik, " +
                    if (table.isValid) "siap" else "belum valid"
            },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = table.displayLabel,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${table.points.size} titik",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Hapus ${table.displayLabel}")
                }
            }
            if (!table.isValid) {
                Text(
                    text = table.problemText ?: "Belum valid",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
            }
            OutlinedTextField(
                value = table.label,
                onValueChange = onLabelChange,
                label = { Text("Label") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("label-${table.id.value}"),
            )
        }
    }
}
