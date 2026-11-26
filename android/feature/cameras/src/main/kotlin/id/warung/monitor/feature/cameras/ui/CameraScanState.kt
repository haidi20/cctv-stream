package id.warung.monitor.feature.cameras.ui

import id.warung.monitor.core.network.ScoredCamera
import id.warung.monitor.core.network.DiscoverySource

/** One row of the Scan CCTV sheet. */
data class ScanRow(
    val host: String,
    val port: Int,
    val brand: String?,
    val source: DiscoverySource,
    val alreadySaved: Boolean,
) {
    val endpoint: String get() = "$host:$port"
    val subtitle: String
        get() = buildString {
            if (brand.isNullOrBlank()) append("Kamera RTSP") else append(brand)
            append(" - ")
            append(
                when (source) {
                    DiscoverySource.Saved -> "tersimpan"
                    DiscoverySource.Onvif -> "ONVIF"
                    DiscoverySource.OpenPort -> "port terbuka"
                },
            )
        }
}

/** What the sheet draws while discovery runs. */
sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Scanning : ScanUiState

    /** No Wi-Fi, so the scan was refused instead of probing a mobile network. */
    data object NoWifi : ScanUiState

    data class Results(
        val rows: List<ScanRow>,
        val fromCache: Boolean,
    ) : ScanUiState {
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    data class Failed(val message: String) : ScanUiState
}

/**
 * Turns ranked discovery results into rows.
 *
 * The source tier comes from discovery itself; "alreadySaved" is a separate
 * flag because a saved camera can also answer ONVIF and should still be marked
 * so the sheet can hide "simpan" for it.
 */
fun List<ScoredCamera>.toScanRows(savedEndpoints: Set<String>): List<ScanRow> = map { scored ->
    ScanRow(
        host = scored.camera.host,
        port = scored.camera.port,
        brand = scored.camera.brand,
        source = scored.source,
        alreadySaved = scored.camera.endpoint in savedEndpoints,
    )
}