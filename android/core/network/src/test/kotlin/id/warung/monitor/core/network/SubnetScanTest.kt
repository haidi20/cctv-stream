package id.warung.monitor.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetScanTest {

    @Test
    fun private_24_subnet_excludes_network_broadcast_and_self() {
        val hosts = SubnetScan.hostsOf("192.168.1.20")

        assertEquals(253, hosts.size)
        assertFalse("network address must not be probed", hosts.contains("192.168.1.0"))
        assertFalse("broadcast must not be probed", hosts.contains("192.168.1.255"))
        assertFalse("the phone itself must not be probed", hosts.contains("192.168.1.20"))
        assertTrue(hosts.contains("192.168.1.1"))
        assertTrue(hosts.contains("192.168.1.254"))
    }

    @Test
    fun ten_slash_eight_and_172_16_are_private_but_public_is_not() {
        assertTrue(SubnetScan.isPrivate("10.4.5.6"))
        assertTrue(SubnetScan.isPrivate("192.168.0.1"))
        assertTrue(SubnetScan.isPrivate("172.16.9.9"))
        assertTrue(SubnetScan.isPrivate("172.31.255.254"))
        assertFalse(SubnetScan.isPrivate("172.32.0.1"))
        assertFalse(SubnetScan.isPrivate("8.8.8.8"))
        assertFalse(SubnetScan.isPrivate("203.0.113.5"))
    }

    @Test
    fun public_or_malformed_addresses_produce_no_scan_targets() {
        // A public address would mean scanning a network we do not own.
        assertTrue(SubnetScan.hostsOf("8.8.8.8").isEmpty())
        assertTrue(SubnetScan.hostsOf("192.168.1").isEmpty())
        assertTrue(SubnetScan.hostsOf("192.168.1.256").isEmpty())
        assertTrue(SubnetScan.hostsOf("192.168.1.a").isEmpty())
        assertTrue(SubnetScan.hostsOf("").isEmpty())
    }

    @Test
    fun a_different_self_address_is_also_excluded() {
        val hosts = SubnetScan.hostsOf("192.168.1.20", self = "192.168.1.99")

        // 254 usable addresses in a /24, minus the anchor and the device itself.
        assertEquals(252, hosts.size)
        assertFalse(hosts.contains("192.168.1.20"))
        assertFalse(hosts.contains("192.168.1.99"))
    }
}