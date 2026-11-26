package id.warung.monitor.core.network

/**
 * Minimal parser for ONVIF WS-Discovery `ProbeMatch` messages.
 *
 * Cameras are inconsistent: some send the XAddrs as one whitespace-separated
 * string, some repeat the element, and real EZVIZ units often skip discovery
 * entirely. The parser therefore never assumes a namespace prefix and returns
 * `null` (not an exception) for anything it does not understand, so a bad
 * packet can never abort the scan.
 */
object WsDiscovery {

    private val xaddrRegex = Regex("""<(?:\w+:)?XAddrs>\s*(.*?)\s*</(?:\w+:)?XAddrs>""", RegexOption.DOT_MATCHES_ALL)
    private val typesRegex = Regex("""<(?:\w+:)?Types>\s*(.*?)\s*</(?:\w+:)?Types>""", RegexOption.DOT_MATCHES_ALL)

    /** Extracts every `rtsp://host:port/path` found, ignoring credentials. */
    fun parseProbeMatch(xml: String): List<DiscoveredCamera> {
        if (!xml.contains("ProbeMatch", ignoreCase = true)) return emptyList()
        val brand = brandOf(typesRegex.find(xml)?.groupValues?.getOrNull(1))
        val xaddrs = xaddrRegex.findAll(xml).flatMap { match ->
            match.groupValues.getOrNull(1).orEmpty().split(Regex("\\s+")).asSequence()
        }
        return xaddrs
            .mapNotNull(::toCamera)
            .map { it.copy(brand = brand) }
            .toList()
    }

    /** `rtsp://10.0.0.5:554/live` -> host + port; credentials are dropped. */
    fun toCamera(xaddr: String): DiscoveredCamera? {
        val trimmed = xaddr.trim()
        if (!trimmed.startsWith("rtsp://", ignoreCase = true)) return null
        val withoutScheme = trimmed.substring(SCHEME_LENGTH)
        val authorityAndPath = withoutScheme.substringAfter('@', withoutScheme)
        val authority = authorityAndPath.substringBefore('/').substringBefore('?')
        if (authority.isEmpty()) return null
        val host = authority.substringBeforeLast(':', authority)
        val portText = if (authority.contains(':')) authority.substringAfterLast(':') else "554"
        val port = portText.toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return DiscoveredCamera(host = host, port = port)
    }

    private const val SCHEME_LENGTH = 7

    private fun brandOf(types: String?): String? {
        val value = types?.lowercase() ?: return null
        return when {
            value.contains("ezviz") -> "EZVIZ"
            value.contains("hikvision") -> "Hikvision"
            value.contains("dahua") -> "Dahua"
            else -> null
        }
    }
}