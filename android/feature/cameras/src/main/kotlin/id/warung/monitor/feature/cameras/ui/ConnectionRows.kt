package id.warung.monitor.feature.cameras.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.warung.monitor.core.network.ConnectionFailure
import id.warung.monitor.core.network.ConnectionLayer
import id.warung.monitor.core.network.LayerOutcome

/** Indonesian label for a layer, shown left of the status glyph. */
internal fun ConnectionLayer.label(): String = when (this) {
    ConnectionLayer.Network -> "Wi-Fi lokal"
    ConnectionLayer.Reachability -> "Kamera terjangkau"
    ConnectionLayer.Credentials -> "Kredensial"
    ConnectionLayer.Stream -> "Stream video"
}

/**
 * One actionable sentence per failure.
 *
 * Never includes an IP, URL, username, or password: the message may be pasted
 * into a chat or a crash report by the user.
 */
internal fun ConnectionFailure.message(): String = when (this) {
    ConnectionFailure.NoNetwork -> "Sambungkan HP ke Wi-Fi warung"
    ConnectionFailure.WrongNetwork -> "HP harus di Wi-Fi lokal, bukan data seluler"
    ConnectionFailure.Unreachable -> "Kamera tidak terjangkau di jaringan ini"
    ConnectionFailure.AuthFailed -> "Kata sandi kamera salah"
    ConnectionFailure.Protocol -> "Format stream tidak didukung"
    ConnectionFailure.Timeout -> "Kamera menjawab tetapi tidak mengirim gambar"
    ConnectionFailure.Closed -> "Kamera menutup koneksi"
    ConnectionFailure.Unknown -> "Tes koneksi gagal"
}

/** Status glyph; `…` doubles as "not run yet" and "in progress". */
internal fun LayerOutcome.glyph(): String = when (this) {
    LayerOutcome.Passed -> "\u2713"
    is LayerOutcome.Failed -> "\u2715"
    LayerOutcome.NotRun -> "\u2026"
}

/**
 * Four fixed rows, in layer order, so a failure can never hide a later green
 * check or imply that an untested layer passed.
 */
@Composable
internal fun ConnectionRows(
    state: ConnectionTestUiState,
    modifier: Modifier = Modifier,
) {
    val report = (state as? ConnectionTestUiState.Done)?.report ?: return
    Column(modifier = modifier.fillMaxWidth().testTag("connection-rows")) {
        ConnectionLayer.entries.forEach { layer ->
            val outcome = report.layers[layer] ?: LayerOutcome.NotRun
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connection-layer-${layer.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = outcome.glyph(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (outcome) {
                        LayerOutcome.Passed -> MaterialTheme.colorScheme.primary
                        is LayerOutcome.Failed -> MaterialTheme.colorScheme.error
                        LayerOutcome.NotRun -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = layer.label(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = when (outcome) {
                        LayerOutcome.Passed -> "OK"
                        is LayerOutcome.Failed -> outcome.failure.message()
                        LayerOutcome.NotRun -> "-"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (outcome) {
                        LayerOutcome.Passed -> MaterialTheme.colorScheme.primary
                        is LayerOutcome.Failed -> MaterialTheme.colorScheme.error
                        LayerOutcome.NotRun -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}