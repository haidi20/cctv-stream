package id.warung.monitor.feature.cameras.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraProfile

/**
 * Stateless camera list: values from [state], intents through callbacks.
 *
 * Width, not orientation, selects the column count. There is no bottom
 * navigation or drawer; opening a camera replaces this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraListScreen(
    state: CameraListState,
    onAdd: () -> Unit,
    onEdit: (CameraId) -> Unit,
    onDelete: (CameraId) -> Unit,
    onOpen: (CameraId) -> Unit,
    onDismissEditor: () -> Unit,
    onEditorLabelChange: (String) -> Unit,
    onEditorSourceChange: (CameraSourceType) -> Unit,
    onEditorHostChange: (String) -> Unit,
    onEditorPortChange: (String) -> Unit,
    onEditorPathChange: (String) -> Unit,
    onEditorUsernameChange: (String) -> Unit,
    onEditorPasswordChange: (String) -> Unit,
    onEditorTestConnection: () -> Unit,
    onEditorSave: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val draft = state.editing
    if (draft != null) {
        CameraEditorScreen(
            draft = draft,
            onLabelChange = onEditorLabelChange,
            onSourceChange = onEditorSourceChange,
            onHostChange = onEditorHostChange,
            onPortChange = onEditorPortChange,
            onPathChange = onEditorPathChange,
            onUsernameChange = onEditorUsernameChange,
            onPasswordChange = onEditorPasswordChange,
            onTestConnection = onEditorTestConnection,
            onSave = onEditorSave,
            onDismiss = onDismissEditor,
        )
        return
    }

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
                title = { Text("Kamera") },
                actions = {
                    IconButton(onClick = onAdd, modifier = Modifier.testTag("add-camera")) {
                        Icon(Icons.Filled.Add, contentDescription = "Tambah kamera")
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
            when {
                state.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                state.isEmpty -> EmptyCameraList(onAdd = onAdd)
                else -> {
                    val columns = when {
                        maxWidth >= 900.dp -> 3
                        maxWidth >= 600.dp -> 2
                        else -> 1
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items = state.cameras, key = { it.id.value }) { camera ->
                            CameraCard(
                                camera = camera,
                                onOpen = { onOpen(camera.id) },
                                onEdit = { onEdit(camera.id) },
                                onDelete = { onDelete(camera.id) },
                            )
                        }
                    }
                }
            }
        }
    }


    state.pendingDelete?.let { camera ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Hapus ${camera.label.value}?") },
            text = { Text("Peta meja kamera ini ikut dihapus. Tindakan ini tidak bisa dibatalkan.") },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, modifier = Modifier.testTag("confirm-delete")) {
                    Text("Hapus")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text("Batal") }
            },
        )
    }
}

@Composable
private fun EmptyCameraList(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Belum ada kamera", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tambahkan CCTV untuk mulai memetakan meja.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.testTag("empty-add-camera")) {
            Text("Tambah Kamera")
        }
    }
}

@Composable
private fun CameraCard(
    camera: CameraProfile,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("camera-${camera.id.value}")) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = camera.label.value,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Ubah ${camera.label.value}")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Hapus ${camera.label.value}")
                }
            }
            Text(
                text = camera.sourceDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (camera.lastOpenedAt != null) {
                AssistChip(
                    onClick = onOpen,
                    label = { Text("Terakhir dibuka") },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open-camera-${camera.id.value}"),
            ) {
                Text("Buka peta")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraEditorScreen(
    draft: CameraEditState,
    onLabelChange: (String) -> Unit,
    onSourceChange: (CameraSourceType) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPathChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(draft.title) },
                navigationIcon = {
                    TextButton(onClick = onDismiss) { Text("Batal") }
                },
                actions = {
                    TextButton(onClick = onSave, modifier = Modifier.testTag("save-camera")) {
                        Text("Simpan")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = draft.label,
                onValueChange = onLabelChange,
                label = { Text("Nama kamera") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("camera-label"),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraSourceType.entries.forEach { sourceType ->
                    FilterChip(
                        selected = draft.sourceType == sourceType,
                        onClick = { onSourceChange(sourceType) },
                        label = { Text(sourceType.label) },
                        modifier = Modifier.testTag("source-${sourceType.name}"),
                    )
                }
            }
            if (draft.sourceType == CameraSourceType.Rtsp) {
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = onHostChange,
                    label = { Text("Host / IP") },
                    placeholder = { Text("192.168.1.64") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("camera-host"),
                )
                OutlinedTextField(
                    value = draft.port,
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("camera-port"),
                )
                OutlinedTextField(
                    value = draft.streamPath,
                    onValueChange = onPathChange,
                    label = { Text("Path stream") },
                    placeholder = { Text("/h264/ch1/sub/av_stream") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("camera-path"),
                )
                OutlinedTextField(
                    value = draft.username,
                    onValueChange = onUsernameChange,
                    label = { Text("Username (opsional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("camera-username"),
                )
                run {
                    // Needed for the connection test only; it is never saved.
                    OutlinedTextField(
                        value = draft.password,
                        onValueChange = onPasswordChange,
                        label = { Text("Kata sandi (hanya untuk tes)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .testTag("camera-password"),
                    )
                }
                Text(
                    text = "Password tidak disimpan di profil. Kredensial RTSP akan " +
                        "memakai Android Keystore saat pipeline preview tersedia.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = draft.connection !is ConnectionTestUiState.Running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("test-connection"),
                ) {
                    if (draft.connection is ConnectionTestUiState.Running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Menguji...")
                    } else {
                        Text("Tes koneksi")
                    }
                }
                ConnectionRows(draft.connection)
            }
            draft.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("camera-error"),
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("save-camera-bottom"),
            ) {
                Text("Simpan")
            }
        }
    }
}