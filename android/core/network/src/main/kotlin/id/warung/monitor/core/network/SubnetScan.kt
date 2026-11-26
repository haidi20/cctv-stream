package id.warung.monitor.core.network

/**
 * Subnet math for the local scan, kept pure so JVM tests need no sockets.
 *
 * Only `/24` prefixes are generated: a warung LAN is small, and scanning a
 * wider range would take long enough that the user assumes the app hung.
 */
object SubnetScan {

    const val MAX_HOSTS: Int = 254

    /**
     * Hosts in the `/24` that contains [ipv4], excluding network, broadcast,
     * and the device itself.
     *
     * Returns an empty list for anything that is not a private IPv4 address:
     * public addresses would mean scanning someone else's network.
     */
    fun hostsOf(ipv4: String, self: String = ipv4): List<String> {
        val octets = parse(ipv4) ?: return emptyList()
        if (!isPrivate(octets)) return emptyList()
        val selfOctets = parse(self)

        val hosts = ArrayList<String>(MAX_HOSTS)
        for (last in 1..254) {
            if (last == octets[3]) continue
            if (selfOctets != null && selfOctets[0] == octets[0] && selfOctets[1] == octets[1] &&
                selfOctets[2] == octets[2] && selfOctets[3] == last
            ) {
                continue
            }
            hosts.add("${octets[0]}.${octets[1]}.${octets[2]}.$last")
        }
        return hosts
    }

    /** RFC 1918 ranges only. */
    fun isPrivate(ipv4: String): Boolean = parse(ipv4)?.let(::isPrivate) == true

    private fun isPrivate(octets: List<Int>): Boolean = when {
        octets[0] == 10 -> true
        octets[0] == 192 && octets[1] == 168 -> true
        octets[0] == 172 && octets[1] in 16..31 -> true
        else -> false
    }

    private fun parse(ipv4: String): List<Int>? {
        val parts = ipv4.trim().split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            value
        }
        return octets
    }
}