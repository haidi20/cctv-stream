package id.warung.monitor.feature.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Custom numeric keypad. Deliberately not a text field: no system keyboard,
 * no autofill, and no clipboard path for the PIN.
 *
 * Uses text glyphs instead of Material icons so the module does not need
 * `material-icons-extended` just for two buttons.
 */
@Composable
fun PinKeypad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onBiometric: (() -> Unit)? = null,
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { digit ->
                    KeypadButton(
                        label = digit.toString(),
                        description = "Angka $digit",
                        onClick = { onDigit(digit) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (onBiometric != null) {
                    KeypadButton(
                        label = "ID",
                        description = "Masuk dengan biometrik",
                        onClick = onBiometric,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            KeypadButton(
                label = "0",
                description = "Angka 0",
                onClick = { onDigit('0') },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            KeypadButton(
                label = "⌫",
                description = "Hapus satu digit",
                onClick = onDelete,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun KeypadButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(64.dp)
            .semantics { contentDescription = description },
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}