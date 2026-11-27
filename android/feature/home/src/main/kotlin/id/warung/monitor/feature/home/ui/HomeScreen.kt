package id.warung.monitor.feature.home.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.model.TableSummary
import id.warung.monitor.core.ui.format.TimeFormat
import id.warung.monitor.feature.cameras.ui.ScanRow
import id.warung.monitor.feature.cameras.ui.ScanUiState
import id.warung.monitor.feature.home.domain.AnalysisStatus
import id.warung.monitor.feature.home.domain.AnalysisTable
import id.warung.monitor.feature.home.domain.DetectionRect
import id.warung.monitor.feature.home.domain.HomeOverlay
import id.warung.monitor.feature.home.domain.HomePowerMode
import id.warung.monitor.feature.home.domain.HomeUiState
import id.warung.monitor.feature.home.domain.StreamErrorKind
import id.warung.monitor.feature.home.domain.StreamUiState

/**
 * Stateless Home. Width, never orientation, selects one column or two panels.
 *
 * [preview] is a slot: the app injects Media3, a demo canvas, or nothing, while
 * this module owns layout, the two totals, and every empty/error rule.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onScan: () -> Unit,
    scanState: ScanUiState = ScanUiState.Idle,
    onRetryScan: () -> Unit = {},
    onOpenCamera: (CameraId) -> Unit,
    onDismissScan: () -> Unit,
    onManageCameras: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    onRetry: () -> Unit,
    preview: @Composable (CameraProfile) -> Unit,
    modifier: Modifier = Modifier,
    powerMode: HomePowerMode = HomePowerMode.Standard,
    appVersion: String = "",
    devMode: Boolean = false,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        if (maxWidth >= 600.dp) {
            WideHome(state, onScan, onManageCameras, onOpenSettings, onLogout, onRetry, preview, powerMode, appVersion, devMode)
        } else {
            CompactHome(state, onScan, onManageCameras, onOpenSettings, onLogout, onRetry, preview, powerMode, appVersion, devMode)
        }
    }

    if (state.overlay is HomeOverlay.Scan) {
        ScanSheet(
            state = state,
            scanState = scanState,
            onOpenCamera = onOpenCamera,
            onRetryScan = onRetryScan,
            onManageCameras = onManageCameras,
            onDismiss = onDismissScan,
        )
    }
}

@Composable
private fun CompactHome(
    state: HomeUiState,
    onScan: () -> Unit,
    onManageCameras: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    onRetry: () -> Unit,
    preview: @Composable (CameraProfile) -> Unit,
    powerMode: HomePowerMode,
    appVersion: String,
    devMode: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home-compact")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VersionBadge(appVersion)
            Spacer(Modifier.weight(1f))
            if (devMode) {
                // Debug builds advertise themselves at the top-right, before
                // the overflow menu, per docs/add-feature.md "mode dev".
                DevelBadge(modifier = Modifier.padding(end = 8.dp))
            }
            OverlayMenu(onManageCameras, onOpenSettings, onLogout)
        }
        VideoCard(state, preview)
        Spacer(Modifier.height(8.dp))
        AnalysisPanel(state)
        Spacer(Modifier.height(8.dp))
        StatusText(state)
        RecalibrationNotice(state, onManageCameras)
        SaverNotice(powerMode)
        Spacer(Modifier.height(12.dp))
        Totals(state)
        Spacer(Modifier.height(16.dp))
        PrimaryActions(state, onScan, onRetry)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WideHome(
    state: HomeUiState,
    onScan: () -> Unit,
    onManageCameras: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    onRetry: () -> Unit,
    preview: @Composable (CameraProfile) -> Unit,
    powerMode: HomePowerMode,
    appVersion: String,
    devMode: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxSize().testTag("home-wide").padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.weight(1.6f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            VideoCard(state, preview)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 420.dp)
                .fillMaxHeight()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VersionBadge(appVersion)
                Spacer(Modifier.weight(1f))
                if (devMode) {
                    DevelBadge(modifier = Modifier.padding(end = 8.dp))
                }
                OverlayMenu(onManageCameras, onOpenSettings, onLogout)
            }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                CameraTitle(state)
                AnalysisPanel(state)
                StatusText(state)
                RecalibrationNotice(state, onManageCameras)
                SaverNotice(powerMode)
                Spacer(Modifier.height(16.dp))
                Totals(state, stacked = true)
            }
            Spacer(Modifier.height(12.dp))
            PrimaryActions(state, onScan, onRetry)
        }
    }
}

/** Small `v0.1.0` chip; hidden when the caller has no version. */
@Composable
private fun VersionBadge(version: String, modifier: Modifier = Modifier) {
    if (version.isBlank()) return
    Text(
        text = "v$version",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("app-version"),
    )
}

/** Debug-only marker; release builds never call this. */
@Composable
private fun DevelBadge(modifier: Modifier = Modifier) {
    Text(
        text = "devel",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("devel-badge"),
    )
}

/**
 * Per-table rows plus the box count, all owned by Rust.
 *
 * Rendered only when Rust has a configuration for the active camera; a
 * stopped engine leaves the panel hidden instead of showing zero tables.
 */
@Composable
private fun AnalysisPanel(state: HomeUiState) {
    val tables = state.tables ?: return
    Card(
        modifier = Modifier.fillMaxWidth().testTag("analysis-panel"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hasil analisa", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    text = analysisCadence(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("analysis-cadence"),
                )
            }
            val analysed = state.lastAnalysisMillis?.let(TimeFormat::clock)
            Text(
                text = if (analysed == null) {
                    "Menunggu frame pertama"
                } else {
                    "Frame dianalisis $analysed · ${state.detections.size} orang terdeteksi"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("analysis-time"),
            )
            Spacer(Modifier.height(8.dp))
            tables.forEach { table ->
                AnalysisRow(table)
            }
        }
    }
}

private fun analysisCadence(state: HomeUiState): String {
    val fps = state.inferenceFps
    val age = state.lastAnalysisAgeMillis
    return when {
        fps <= 0.0 -> "belum aktif"
        age == null -> "target ${formatFps(fps)} FPS"
        age < 1_000L -> "target ${formatFps(fps)} FPS"
        else -> "target ${formatFps(fps)} FPS · ${age / 1_000L} dtk lalu"
    }
}

private fun formatFps(fps: Double): String =
    if (fps % 1.0 == 0.0) fps.toInt().toString() else String.format("%.1f", fps)

@Composable
private fun AnalysisRow(table: AnalysisTable) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("table-${table.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = table.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when (table.status) {
                AnalysisStatus.Available -> "kosong"
                AnalysisStatus.Occupied -> "${table.persons} orang"
                AnalysisStatus.Unknown -> "tidak terpantau"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (table.status) {
                AnalysisStatus.Available -> MaterialTheme.colorScheme.primary
                AnalysisStatus.Occupied -> MaterialTheme.colorScheme.error
                AnalysisStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Thin boxes over the preview. Coordinates are already normalised and clamped
 * by Rust; nothing here asks the engine for counts or geometry.
 */
@Composable
private fun DetectionOverlay(boxes: List<DetectionRect>, modifier: Modifier = Modifier) {
    if (boxes.isEmpty()) return
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 2f)
        boxes.forEach { box ->
            drawRect(
                color = Color(0xFF4CD964),
                topLeft = Offset(box.left * size.width, box.top * size.height),
                size = Size(box.width * size.width, box.height * size.height),
                style = stroke,
            )
        }
    }
}

@Composable
private fun CameraTitle(state: HomeUiState) {
    val camera = state.camera ?: return
    Text(camera.label.value, style = MaterialTheme.typography.titleMedium)
    Text(
        text = camera.sourceDescription,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun VideoCard(
    state: HomeUiState,
    preview: @Composable (CameraProfile) -> Unit,
) {
    val camera = state.camera
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
    ) {
        when {
            camera == null -> EmptyPreview()
            state.stream is StreamUiState.Failed -> ErrorPreview(state.stream.kind)
            else -> preview(camera)
        }
        // Boxes come from Rust in 0..1 frame space; the Canvas applies the
        // letterboxed ImageBitmap scale, so Kotlin never converts pixels.
        DetectionOverlay(
            boxes = state.detections,
            modifier = Modifier.fillMaxSize().testTag("detection-overlay"),
        )
        if (state.stream is StreamUiState.Live) {
            LiveChip(modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        }
        if (state.demoMode) {
            Text(
                text = "DEMO",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun LiveChip(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        Spacer(Modifier.size(6.dp))
        Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyPreview() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Belum ada kamera", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            "Sambungkan CCTV warung untuk melihat status meja.",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorPreview(kind: StreamErrorKind) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(kind.message, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            if (kind.isNetworkProblem) {
                "Nyalakan Wi-Fi lokal, lalu coba lagi."
            } else {
                "Pastikan HP dan CCTV ada di Wi-Fi yang sama."
            },
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Tells the user that the camera moved or the resolution changed. The polygons
 * are never moved automatically: a stale map would silently count the wrong
 * tables, so recalibration is an explicit action in Kelola kamera & meja.
 */
@Composable
private fun RecalibrationNotice(state: HomeUiState, onManageCameras: () -> Unit) {
    if (!state.needsRecalibration) return
    Column(modifier = Modifier.padding(top = 4.dp).testTag("recalibration-notice")) {
        Text(
            text = "Peta meja perlu digambar ulang: rasio video berubah. Poligon tidak " +
                "digeser otomatis.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(
            onClick = onManageCameras,
            modifier = Modifier.testTag("recalibration-action"),
        ) {
            Text("Buka peta meja")
        }
    }
}

/**
 * Explains the reduced cadence instead of leaving the user to wonder why
 * detection is slower. Also names the action that restores the profile.
 */
@Composable
private fun SaverNotice(powerMode: HomePowerMode) {
    if (powerMode != HomePowerMode.Saver) return
    Text(
        text = "Mode hemat: deteksi lebih lambat. Periksa ulang perangkat di Pengaturan " +
            "untuk menaikkan profil.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp).testTag("power-saver-note"),
    )
}

@Composable
private fun StatusText(state: HomeUiState) {
    val text = when (val stream = state.stream) {
        StreamUiState.Idle -> if (state.hasCamera) "Belum terhubung" else "Belum ada kamera"
        StreamUiState.Connecting -> "Menghubungkan ke kamera…"
        is StreamUiState.Live -> {
            val updated = state.lastUpdatedMillis?.let(TimeFormat::clock)
            if (updated == null) "● Terhubung" else "● Terhubung · diperbarui $updated"
        }
        is StreamUiState.Reconnecting -> {
            val seconds = (stream.nextRetryInMs / 1_000L).coerceAtLeast(1L)
            "Menyambung ulang (percobaan ${stream.attempt}, ${seconds} dtk)…"
        }
        is StreamUiState.Failed -> stream.kind.message
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("stream-status"),
    )
}

@Composable
private fun Totals(state: HomeUiState, stacked: Boolean = false) {
    val summary = state.summary
    if (stacked) {
        CountCard("Kosong", summary?.available, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        CountCard("Terisi", summary?.occupied, Modifier.fillMaxWidth())
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CountCard("Kosong", summary?.available, Modifier.weight(1f))
            CountCard("Terisi", summary?.occupied, Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(8.dp))
    UnknownNote(state, summary)
}

@Composable
private fun CountCard(label: String, value: Int?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.testTag("count-$label"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Crossfade(targetState = value, label = "count-$label") { shown ->
                Text(
                    text = shown?.toString() ?: "—",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UnknownNote(state: HomeUiState, summary: TableSummary?) {
    val unknown = summary != null && summary.unknown > 0
    val text = when {
        unknown -> "${summary.unknown} meja tidak terpantau"
        state.stream is StreamUiState.Connecting -> "Menghubungkan ke kamera…"
        summary == null -> "Status meja belum tersedia"
        else -> null
    }
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (unknown) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("unknown-note"),
    )
}
@Composable
private fun PrimaryActions(state: HomeUiState, onScan: () -> Unit, onRetry: () -> Unit) {
    if (state.stream is StreamUiState.Failed) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry, modifier = Modifier.weight(1f).height(56.dp)) {
                Text("Coba lagi")
            }
            Button(
                onClick = onScan,
                modifier = Modifier.weight(1f).height(56.dp).testTag("scan-button"),
            ) {
                Text("Scan ulang")
            }
        }
        return
    }
    Button(
        onClick = onScan,
        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("scan-button"),
    ) {
        Text("Scan CCTV")
    }
    if (state.demoMode) {
        Text(
            text = "Mode demo: belum ada frame kamera nyata.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun OverlayMenu(
    onManageCameras: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Menu lain")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Kelola kamera & meja") },
                onClick = { expanded = false; onManageCameras() },
            )
            DropdownMenuItem(
                text = { Text("Sinkronisasi server") },
                onClick = { expanded = false; onOpenSettings() },
            )
            DropdownMenuItem(
                text = { Text("Keluar") },
                onClick = { expanded = false; onLogout() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanSheet(
    state: HomeUiState,
    scanState: ScanUiState,
    onOpenCamera: (CameraId) -> Unit,
    onRetryScan: () -> Unit,
    onManageCameras: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ScanSheetBody(
            state = state,
            scanState = scanState,
            onOpenSaved = { camera -> onOpenCamera(camera.id) },
            onRetryScan = onRetryScan,
            onManageCameras = onManageCameras,
        )
        Spacer(Modifier.height(24.dp))
    }
}
@Composable
internal fun ScanSheetBody(
    state: HomeUiState,
    scanState: ScanUiState,
    onOpenSaved: (CameraProfile) -> Unit,
    onRetryScan: () -> Unit,
    onManageCameras: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Scan CCTV", style = MaterialTheme.typography.titleLarge)
        Text(
            "Pilih kamera yang tersimpan. Satu kamera aktif dalam satu waktu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        DiscoveryStatus(scanState, onRetryScan, onManageCameras)
        val discovered = (scanState as? ScanUiState.Results)?.rows.orEmpty()
        val savedInRows = discovered.filter { it.alreadySaved }
            .map { it.host to it.port }
            .toSet()
        val savedElsewhere = state.cameras.filter { camera ->
            val rtsp = camera.source as? CameraSource.Rtsp
            rtsp == null || (rtsp.host to rtsp.port) !in savedInRows
        }
        if (savedElsewhere.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Kamera tersimpan", style = MaterialTheme.typography.labelLarge)
            savedElsewhere.forEach { camera ->
                SavedCameraRow(camera = camera, onClick = { onOpenSaved(camera) })
            }
        }
        if (discovered.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Ditemukan di Wi-Fi", style = MaterialTheme.typography.labelLarge)
            discovered.forEach { row ->
                val saved = state.cameras.firstOrNull { camera ->
                    val rtsp = camera.source as? CameraSource.Rtsp
                    row.alreadySaved && rtsp != null && rtsp.host == row.host && rtsp.port == row.port
                }
                DiscoveredRow(row = row, saved = saved, onOpenSaved = onOpenSaved, onManageCameras = onManageCameras)
            }
        }
        if (savedElsewhere.isEmpty() && discovered.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Belum ada kamera tersimpan.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onManageCameras, modifier = Modifier.fillMaxWidth()) {
                Text("Tambah kamera")
            }
        } else {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onManageCameras, modifier = Modifier.fillMaxWidth()) {
                Text("Kelola kamera & meja")
            }
        }
    }
}

@Composable
private fun DiscoveryStatus(
    scanState: ScanUiState,
    onRetryScan: () -> Unit,
    onManageCameras: () -> Unit,
) {
    when (scanState) {
        ScanUiState.Idle -> Unit
        ScanUiState.Scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).testTag("scan-progress"),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(10.dp))
            Text("Mencari kamera di Wi-Fi ini...", style = MaterialTheme.typography.bodyMedium)
        }
        ScanUiState.NoWifi -> Column(modifier = Modifier.testTag("scan-no-wifi")) {
            Text("Sambungkan ke Wi-Fi dulu", style = MaterialTheme.typography.titleSmall)
            Text(
                "Scan hanya berjalan di jaringan lokal; data seluler tidak dipakai.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onManageCameras) { Text("Tambah manual") }
        }
        is ScanUiState.Results -> Column {
            if (scanState.fromCache) {
                Text(
                    "Hasil cache dari scan terakhir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("scan-from-cache"),
                )
            }
            if (scanState.isEmpty) {
                Column(modifier = Modifier.testTag("scan-empty")) {
                    Text("Tidak ada kamera ditemukan.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetryScan, modifier = Modifier.testTag("scan-retry")) {
                        Text("Cari lagi")
                    }
                }
            }
        }
        is ScanUiState.Failed -> Column(modifier = Modifier.testTag("scan-failed")) {
            Text(scanState.message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetryScan) { Text("Cari lagi") }
        }
    }
}

@Composable
private fun SavedCameraRow(camera: CameraProfile, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(camera.label.value, style = MaterialTheme.typography.titleMedium)
            Text(
                text = camera.sourceDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiscoveredRow(
    row: ScanRow,
    saved: CameraProfile?,
    onOpenSaved: (CameraProfile) -> Unit,
    onManageCameras: () -> Unit,
) {
    Card(
        onClick = {
            if (saved != null) onOpenSaved(saved) else onManageCameras()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("discovered-${row.endpoint}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(saved?.label?.value ?: row.endpoint, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = saved?.sourceDescription ?: row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!row.alreadySaved) {
                Text("Baru", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

