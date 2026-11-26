package id.warung.monitor.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WsDiscoveryTest {

    @Test
    fun a_probe_match_yields_host_and_port_without_credentials() {
        val xml = """
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope"
                xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
              <SOAP-ENV:Body>
                <tds:ProbeMatch>
                  <tds:Types>tds:Device</tds:Types>
                  <tds:XAddrs>rtsp://admin:rahasia@192.168.1.64:554/live</tds:XAddrs>
                </tds:ProbeMatch>
              </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
        """.trimIndent()

        val result = WsDiscovery.parseProbeMatch(xml)

        assertEquals(1, result.size)
        assertEquals("192.168.1.64", result.single().host)
        assertEquals(554, result.single().port)
        assertEquals("192.168.1.64:554", result.single().endpoint)
        assertTrue("credentials must not leak into the result", !result.single().endpoint.contains("rahasia"))
    }

    @Test
    fun multiple_xaddrs_entries_and_whitespace_separated_addresses_are_all_read() {
        val xml = """
            <d:ProbeMatch>
              <d:XAddrs>rtsp://10.0.0.5:554/a rtsp://10.0.0.6:8554/b</d:XAddrs>
              <d:XAddrs>rtsp://10.0.0.7/stream</d:XAddrs>
            </d:ProbeMatch>
        """.trimIndent()

        val result = WsDiscovery.parseProbeMatch(xml)

        assertEquals(
            listOf("10.0.0.5:554", "10.0.0.6:8554", "10.0.0.7:554"),
            result.map { it.endpoint },
        )
    }

    @Test
    fun a_brand_is_only_reported_when_the_types_element_names_it() {
        fun message(types: String) = """
            <d:ProbeMatch><d:Types>$types</d:Types><d:XAddrs>rtsp://10.0.0.5/1</d:XAddrs></d:ProbeMatch>
        """.trimIndent()

        assertEquals("EZVIZ", WsDiscovery.parseProbeMatch(message("dn:NetworkVideoTransmitter EZVIZ")).single().brand)
        assertEquals("Hikvision", WsDiscovery.parseProbeMatch(message("hikvision")).single().brand)
        assertNull(WsDiscovery.parseProbeMatch(message("dn:NetworkVideoTransmitter")).single().brand)
    }

    @Test
    fun garbage_and_non_rtsp_xaddrs_are_ignored() {
        assertTrue(WsDiscovery.parseProbeMatch("not xml at all").isEmpty())
        assertTrue(WsDiscovery.parseProbeMatch("<d:ProbeMatch><d:XAddrs>http://10.0.0.5/</d:XAddrs></d:ProbeMatch>").isEmpty())
        assertTrue(WsDiscovery.parseProbeMatch("<d:ProbeMatch><d:XAddrs>rtsp://:554/x</d:XAddrs></d:ProbeMatch>").isEmpty())
    }

    @Test
    fun a_mixed_case_scheme_is_still_parsed() {
        assertEquals(554, WsDiscovery.toCamera("Rtsp://192.168.0.7/stream")?.port)
        assertEquals("192.168.0.7", WsDiscovery.toCamera("RTSP://192.168.0.7/live")?.host)
    }

    @Test
    fun an_xaddr_without_a_port_defaults_to_554() {
        assertEquals(554, WsDiscovery.toCamera("rtsp://192.168.0.7/stream")?.port)
        assertEquals(8554, WsDiscovery.toCamera("rtsp://192.168.0.7:8554/stream")?.port)
        assertNull(WsDiscovery.toCamera("rtsp://192.168.0.7:notaport/stream"))
    }
}