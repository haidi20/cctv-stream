package id.warung.monitor.feature.cameras.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.network.DiscoveredCamera
import id.warung.monitor.core.network.ScanOutcome
import id.warung.monitor.feature.cameras.data.CameraRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Seam over `core:network` discovery so JVM tests never open a socket.
 *
 * The real implementation delegates to `CameraDiscovery`, which already
 * refuses to probe a mobile network and keeps a 10-minute cache.
 */
fun interface CameraScanSource {
    suspend fun scan(saved: List<DiscoveredCamera>): ScanOutcome
}

/**
 * Owns the Scan CCTV sheet: one scan at a time, cancellable, results replaced
 * rather than appended.
 *
 * The ViewModel resolves "already saved" by endpoint, so a camera the user
 * added manually is still recognised after a scan.
 */
@HiltViewModel
class CameraScanViewModel @Inject constructor(
    private val repository: CameraRepository,
    private val source: CameraScanSource,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Starts a fresh scan; a running one is cancelled first. */
    fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = ScanUiState.Scanning
            val saved = runCatching { repository.all() }
                .getOrDefault(emptyList())
                .mapNotNull { profile ->
                    (profile.source as? CameraSource.Rtsp)?.let { rtsp ->
                        DiscoveredCamera(host = rtsp.host, port = rtsp.port)
                    }
                }
            val savedEndpoints = saved.map { it.endpoint }.toSet()
            val outcome = try {
                source.scan(saved)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.value = ScanUiState.Failed("Scan gagal. Periksa Wi-Fi lalu coba lagi.")
                return@launch
            }
            _state.value = when (outcome) {
                ScanOutcome.NoWifi -> ScanUiState.NoWifi
                is ScanOutcome.Found -> ScanUiState.Results(
                    rows = outcome.cameras.toScanRows(savedEndpoints),
                    fromCache = outcome.fromCache,
                )
            }
        }
    }

    /** Sheet dismissed: stop probing immediately, keep no partial results. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = ScanUiState.Idle
    }

    fun dismissResults() {
        job?.cancel()
        job = null
        _state.value = ScanUiState.Idle
    }
}