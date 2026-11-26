package id.warung.monitor.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.warung.monitor.feature.onboarding.domain.CheckStatus
import id.warung.monitor.feature.onboarding.domain.CheckTone
import id.warung.monitor.feature.onboarding.domain.DeviceCheckRow
import id.warung.monitor.feature.onboarding.domain.DeviceReport
import id.warung.monitor.feature.onboarding.domain.OnboardingState
import id.warung.monitor.feature.onboarding.domain.toneOf

/** Amber is fixed rather than theme-derived so warning never renders as red. */
private val WarningAmber = Color(0xFF8A5A00)
private val WarningAmberContainer = Color(0xFFFFE0A3)

/**
 * Stateless diagnostic surface.
 *
 * One column at every width; the extra space on tablets becomes margin, not a
 * second panel. The check button owns the loading state and is disabled while
 * [checking] so it cannot be pressed twice.
 */
@Composable
fun DeviceCheckScreen(
    state: OnboardingUiState,
    checking: Boolean,
    onRunCheck: () -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onCopyReport: () -> Unit,
    onCloseApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Pemeriksaan perangkat",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (state) {
                    OnboardingUiState.Loading -> LoadingBody()
                    OnboardingUiState.Check -> IntroBody(checking = checking, onRunCheck = onRunCheck)
                    is OnboardingUiState.ReportBody -> ReportContent(
                        report = state.report,
                        checking = checking,
                        onRunCheck = onRunCheck,
                        onContinue = onContinue,
                        onRetry = onRetry,
                        onCopyReport = onCopyReport,
                        onCloseApp = onCloseApp,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("device-check-loading"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Memuat hasil pemeriksaan…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IntroBody(checking: Boolean, onRunCheck: () -> Unit) {
    Text(
        text = "Sebelum masuk, aplikasi memeriksa apakah perangkat ini mampu menjalankan " +
            "deteksi meja secara lokal.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrimaryCheckButton(checking = checking, onRunCheck = onRunCheck)
}

@Composable
private fun ReportContent(
    report: DeviceReport,
    checking: Boolean,
    onRunCheck: () -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onCopyReport: () -> Unit,
    onCloseApp: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in report.rows) {
            CheckRow(row = row, checking = checking)
        }
    }
    if (report.isBlocked) {
        BlockedActions(report = report, onCopyReport = onCopyReport, onCloseApp = onCloseApp)
    } else {
        Spacer(Modifier.height(4.dp))
        if (report.hasWarning) {
            Text(
                text = report.warningReasons.joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                color = WarningAmber,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(WarningAmberContainer)
                    .padding(12.dp)
                    .testTag("device-check-warning"),
            )
            Button(
                onClick = onContinue,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth().testTag("device-check-continue"),
            ) {
                Text("Lanjutkan dengan mode hemat")
            }
            TextButton(
                onClick = onRetry,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth().testTag("device-check-retry"),
            ) {
                Text("Periksa ulang")
            }
        } else {
            Text(
                text = "Perangkat siap. Lanjutkan untuk membuat atau memasukkan PIN.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryCheckButton(checking = checking, onRunCheck = onRunCheck)
        }
    }
}

@Composable
private fun PrimaryCheckButton(checking: Boolean, onRunCheck: () -> Unit) {
    Button(
        onClick = onRunCheck,
        enabled = !checking,
        modifier = Modifier.fillMaxWidth().testTag("device-check-run"),
    ) {
        if (checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).testTag("device-check-progress"),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(8.dp))
            Text("Memeriksa…")
        } else {
            Text("Periksa perangkat")
        }
    }
}

@Composable
private fun CheckRow(row: DeviceCheckRow, checking: Boolean) {
    val tone = toneOf(row.status)
    val statusColor = when (tone) {
        CheckTone.Positive -> MaterialTheme.colorScheme.primary
        CheckTone.Caution -> WarningAmber
        CheckTone.Blocked -> MaterialTheme.colorScheme.error
        CheckTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    var expanded by rememberSaveable(row.id.name) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device-check-row-${row.id.name}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.id.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = row.status.label,
                style = MaterialTheme.typography.labelLarge,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = row.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val technical = row.technical
        if (technical != null) {
            TextButton(
                onClick = { expanded = !expanded },
                enabled = !checking,
                modifier = Modifier.testTag("device-check-detail-${row.id.name}"),
            ) {
                Text(if (expanded) "Sembunyikan detail" else "Lihat detail")
            }
            if (expanded) {
                Text(
                    text = technical,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BlockedActions(
    report: DeviceReport,
    onCopyReport: () -> Unit,
    onCloseApp: () -> Unit,
) {
    Text(
        text = report.blockedReason
            ?: "Perangkat ini belum bisa menjalankan pemantauan meja.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().testTag("device-check-blocked"),
    )
    Button(
        onClick = onCopyReport,
        modifier = Modifier.fillMaxWidth().testTag("device-check-copy"),
    ) {
        Text("Salin laporan perangkat")
    }
    OutlinedButton(
        onClick = onCloseApp,
        modifier = Modifier.fillMaxWidth().testTag("device-check-close"),
    ) {
        Text("Tutup aplikasi")
    }
    Text(
        text = "Aplikasi tidak membuka kamera atau pemantauan pada perangkat ini.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** What the screen can draw; approval is handled by the route. */
sealed interface OnboardingUiState {
    data object Loading : OnboardingUiState
    data object Check : OnboardingUiState
    data class ReportBody(val report: DeviceReport) : OnboardingUiState
}

/** Maps domain state to a drawable body. */
fun toUiState(state: OnboardingState): OnboardingUiState = when (state) {
    OnboardingState.Loading -> OnboardingUiState.Loading
    OnboardingState.Check -> OnboardingUiState.Check
    OnboardingState.Checking -> OnboardingUiState.Check
    is OnboardingState.Ready -> OnboardingUiState.ReportBody(state.report)
    is OnboardingState.Blocked -> OnboardingUiState.ReportBody(state.report)
    is OnboardingState.Approved -> OnboardingUiState.Loading
}
