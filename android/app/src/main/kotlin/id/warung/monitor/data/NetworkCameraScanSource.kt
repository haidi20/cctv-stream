package id.warung.monitor.data

import id.warung.monitor.core.network.CameraDiscovery
import id.warung.monitor.core.network.CameraPortScanner
import id.warung.monitor.core.network.DiscoveredCamera
import id.warung.monitor.core.network.LocalNetwork
import id.warung.monitor.core.network.ScanOutcome
import id.warung.monitor.core.network.SocketTcpProbe
import id.warung.monitor.core.network.WsDiscoverySource
import id.warung.monitor.feature.cameras.ui.CameraScanSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real Scan CCTV: subnet probe + WS-Discovery, merged and cached.
 *
 * The merge happens inside `CameraDiscovery`, so this adapter only has to
 * re-tag results with their discovery tier for the sheet.
 */
@Singleton
class NetworkCameraScanSource @Inject constructor(
    localNetwork: LocalNetwork,
    wsDiscovery: WsDiscoverySource,
) : CameraScanSource {

    private val discovery = CameraDiscovery(
        localNetwork = localNetwork,
        wsDiscovery = wsDiscovery,
        scanner = CameraPortScanner(SocketTcpProbe()),
    )

    override suspend fun scan(saved: List<DiscoveredCamera>): ScanOutcome = withContext(Dispatchers.IO) {
        discovery.scan(saved)
    }
}