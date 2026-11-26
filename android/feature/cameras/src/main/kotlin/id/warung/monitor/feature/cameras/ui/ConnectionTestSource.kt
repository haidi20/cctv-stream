package id.warung.monitor.feature.cameras.ui

import id.warung.monitor.core.network.ConnectionReport
import id.warung.monitor.core.network.RtspEndpoint

/**
 * Seam over `core:network`'s four-layer tester so the ViewModel is a plain JVM
 * test and the real socket work stays in the app module.
 *
 * The receiver must treat [RtspEndpoint.secret] as borrowed: no caching, no
 * copying into a field, no logging.
 */
fun interface ConnectionTestSource {
    suspend fun test(endpoint: RtspEndpoint): ConnectionReport
}