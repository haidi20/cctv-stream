package id.warung.monitor.core.network

/**
 * Merges discovery sources into one list keyed by `host:port`.
 *
 * Ranking follows the product rule: cameras the user already saved come first,
 * then ONVIF responders, then plain open-port hits. Stable within a tier so the
 * sheet does not reshuffle while the user is reading it.
 */
object DiscoveryMerge {

    fun merge(
        saved: List<DiscoveredCamera>,
        onvif: List<DiscoveredCamera>,
        openPorts: List<DiscoveredCamera>,
    ): List<ScoredCamera> {
        val seen = LinkedHashMap<String, ScoredCamera>()
        fun add(camera: DiscoveredCamera, source: DiscoverySource) {
            val key = camera.endpoint
            val existing = seen[key]
            if (existing == null) {
                seen[key] = ScoredCamera(camera, source)
            } else if (source.rank < existing.source.rank) {
                // Keep a brand learned from ONVIF even when the port probe wins.
                seen[key] = ScoredCamera(
                    camera = camera.copy(brand = existing.camera.brand ?: camera.brand),
                    source = source,
                )
            } else if (existing.camera.brand == null && camera.brand != null) {
                seen[key] = existing.copy(camera = existing.camera.copy(brand = camera.brand))
            }
        }
        saved.forEach { add(it, DiscoverySource.Saved) }
        onvif.forEach { add(it, DiscoverySource.Onvif) }
        openPorts.forEach { add(it, DiscoverySource.OpenPort) }

        return seen.values.sortedWith(
            compareBy(
                { it.source.rank },
                { it.camera.host.lastSegment() },
                { it.camera.port },
            ),
        )
    }

    private fun String.lastSegment(): Int = substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE
}

enum class DiscoverySource(val rank: Int) {
    Saved(0),
    Onvif(1),
    OpenPort(2),
}

data class ScoredCamera(val camera: DiscoveredCamera, val source: DiscoverySource)