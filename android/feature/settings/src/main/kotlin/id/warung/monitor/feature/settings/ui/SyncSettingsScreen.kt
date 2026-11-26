package id.warung.monitor.feature.settings.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.warung.monitor.feature.settings.domain.SyncSettingsState

/**
 * Stateless VPS settings screen.
 *
 * Sync is optional and off by default; the copy states that the camera stream
 * never leaves the device and only table metadata is queued. Width, not
 * orientation, caps the form so it stays readable on tablets and in landscape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    state: SyncSettingsState,
    onBaseUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onClearToken: () -> Unit,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
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
                title = { Text("Sinkronisasi server") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.testTag("settings-loading"))
                    return@Column
                }

                Text(
                    text = "Sinkronisasi bersifat opsional. Tanpa alamat server, aplikasi " +
                        "tetap memantau meja secara lokal dan menyimpan event di antrean.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Hanya metadata meja (status dan waktu) yang dikirim. Video CCTV " +
                        "tidak pernah keluar dari perangkat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("Alamat server") },
                    placeholder = { Text("https://vps.contoh.id") },
                    singleLine = true,
                    enabled = !state.saving,
                    isError = state.error != null,
                    supportingText = {
                        Text(state.error ?: "Wajib https:// dan tanpa garis miring di akhir.")
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("sync-base-url"),
                )

                OutlinedTextField(
                    value = state.tokenDraft,
                    onValueChange = onTokenChange,
                    label = { Text("Token perangkat") },
                    singleLine = true,
                    enabled = !state.saving,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        Text(
                            if (state.hasSavedToken) {
                                "Token tersimpan. Kosongkan untuk mempertahankannya."
                            } else {
                                "Opsional bila server belum memakai autentikasi."
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("sync-token"),
                )

                Text(
                    text = if (state.isConfigured) "Server terpasang" else "Belum diatur",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("sync-status"),
                )

                Button(
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth().testTag("sync-save"),
                ) {
                    Text(if (state.saving) "Menyimpan…" else "Simpan")
                }

                if (state.hasSavedToken) {
                    OutlinedButton(
                        onClick = onClearToken,
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth().testTag("sync-clear-token"),
                    ) {
                        Text("Hapus token tersimpan")
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Catatan", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Event dikirim berurutan dan hanya dihapus setelah server " +
                                "membalas. Bila jaringan putus, antrean tetap aman di perangkat.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
