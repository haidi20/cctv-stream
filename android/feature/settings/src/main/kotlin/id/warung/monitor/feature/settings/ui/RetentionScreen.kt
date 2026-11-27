package id.warung.monitor.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import id.warung.monitor.core.sync.RetentionWindow
import id.warung.monitor.feature.settings.domain.RetentionState

/**
 * Stateless "Penyimpanan & Riwayat" screen (`docs/android-sync.md` section 7).
 *
 * It reports the current database size and the four documented windows. Nothing
 * here deletes data: the save button only stores the choice, and the prune
 * worker applies it on its next pass, so a large `DELETE` never blocks the UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetentionScreen(
    state: RetentionState,
    onSelect: (RetentionWindow) -> Unit,
    onSave: () -> Unit,
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
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Penyimpanan & Riwayat") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("retention-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                    CircularProgressIndicator(modifier = Modifier.testTag("retention-loading"))
                    return@Column
                }

                HistoryCard(state)

                Text("Masa simpan riwayat", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Hanya event yang sudah terkirim ke server yang dihapus. " +
                        "Antrean yang belum terkirim selalu dipertahankan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RetentionWindow.entries.forEach { window ->
                    RetentionOption(
                        window = window,
                        selected = window == state.window,
                        enabled = !state.saving,
                        onSelect = onSelect,
                    )
                }

                if (state.showsShortRetentionWarning) {
                    ShortRetentionWarning()
                }

                if (state.error != null) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("retention-error"),
                    )
                }

                Button(
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth().testTag("retention-save"),
                ) {
                    Text(if (state.saving) "Menyimpan…" else "Simpan")
                }

                Text(
                    text = "Perubahan berlaku pada pemangkasan berikutnya; data tidak " +
                        "langsung dihapus saat disimpan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(state: RetentionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Riwayat lokal", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${state.rows} event tersimpan",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("retention-rows"),
            )
            Text(
                text = "Ukuran basis data " + StorageSize.format(state.databaseBytes),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("retention-size"),
            )
            if (state.atRowLimit) {
                Text(
                    text = "Batas ${state.rowLimit} baris tersimpan tercapai; " +
                        "event terlama dihapus lebih dulu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RetentionOption(
    window: RetentionWindow,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (RetentionWindow) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onSelect(window) },
            )
            .testTag("retention-option-${window.days}")
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text("${window.days} hari", style = MaterialTheme.typography.bodyLarge)
            if (window == RetentionWindow.DEFAULT) {
                Text(
                    text = "Bawaan",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShortRetentionWarning() {
    Card(modifier = Modifier.fillMaxWidth().testTag("retention-warning")) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Riwayat lokal cepat hilang", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Dengan 3 hari, riwayat di perangkat lebih cepat terhapus. " +
                    "Data yang sudah terkirim tetap tersimpan di server.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
