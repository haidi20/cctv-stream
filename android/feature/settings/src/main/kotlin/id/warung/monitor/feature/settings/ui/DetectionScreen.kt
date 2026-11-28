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
import id.warung.monitor.core.model.DetectionPreset
import id.warung.monitor.feature.settings.domain.DetectionState

/**
 * Stateless "Deteksi" screen (`docs/detection-quality-todo.md` Q-05).
 *
 * Two radios, one save button, and no numeric field. The copy says plainly what
 * each choice trades away, because "confidence threshold 0.45" means nothing to
 * the person running the warung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionScreen(
    state: DetectionState,
    onSelect: (DetectionPreset) -> Unit,
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
                title = { Text("Deteksi orang") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detection-back")) {
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
                    CircularProgressIndicator(modifier = Modifier.testTag("detection-loading"))
                    return@Column
                }

                Text(
                    text = "Pilih seberapa yakin aplikasi sebelum menandai seseorang di " +
                        "layar. Hanya kotak deteksi yang berubah; jumlah meja tetap " +
                        "dihitung Rust.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                DetectionPreset.entries.forEach { preset ->
                    DetectionOption(
                        preset = preset,
                        selected = preset == state.preset,
                        enabled = !state.saving,
                        onSelect = onSelect,
                    )
                }

                if (state.showsUnknownValueNote) {
                    Card(modifier = Modifier.fillMaxWidth().testTag("detection-unknown-note")) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Pilihan lama tidak dikenali", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Perangkat menyimpan pilihan deteksi dari versi lain. " +
                                    "Sementara memakai ${state.preset.label}; simpan untuk " +
                                    "menggantinya.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (state.error != null) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("detection-error"),
                    )
                }

                Button(
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth().testTag("detection-save"),
                ) {
                    Text(if (state.saving) "Menyimpan..." else "Simpan")
                }

                Text(
                    text = "Pilihan berlaku saat analisa kamera berikutnya dimulai; " +
                        "stream yang sedang berjalan tidak terputus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetectionOption(
    preset: DetectionPreset,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (DetectionPreset) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onSelect(preset) },
            )
            .testTag("detection-option-${preset.wireName}")
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preset.label, style = MaterialTheme.typography.bodyLarge)
                if (preset == DetectionPreset.DEFAULT) {
                    Text(
                        text = "  Bawaan",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = presetExplanation(preset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One sentence per preset; the trade-off is the whole point of the choice. */
internal fun presetExplanation(preset: DetectionPreset): String = when (preset) {
    DetectionPreset.Balanced ->
        "Menangkap orang yang lebih jauh dari kamera; sesekali kursi ikut tertandai."
    DetectionPreset.Precise ->
        "Lebih sedikit salah tandai; orang yang jauh atau terhalang bisa terlewat."
}
