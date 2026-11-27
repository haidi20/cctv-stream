package id.warung.monitor.core.network

import id.warung.monitor.core.model.CameraDeviceId
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryMergeTest {

    private fun cam(endpoint: String, brand: String? = null): DiscoveredCamera {
        val host = endpoint.substringBefore(':')
        val port = endpoint.substringAfter(':').toInt()
        return DiscoveredCamera(host = host, port = port, brand = brand)
    }

    @Test
    fun duplicate_endpoints_appear_once_and_keep_the_best_source() {
        val merged = DiscoveryMerge.merge(
            saved = listOf(cam("192.168.1.5:554")),
            onvif = listOf(cam("192.168.1.5:554", brand = "EZVIZ")),
            openPorts = listOf(cam("192.168.1.5:554")),
        )

        assertEquals(1, merged.size)
        assertEquals(DiscoverySource.Saved, merged.single().source)
        assertEquals("a brand learned from ONVIF is kept", "EZVIZ", merged.single().camera.brand)
    }

    @Test
    fun order_is_saved_then_onvif_then_open_port() {
        val merged = DiscoveryMerge.merge(
            saved = listOf(cam("192.168.1.9:554")),
            onvif = listOf(cam("192.168.1.5:554")),
            openPorts = listOf(cam("192.168.1.2:554")),
        )

        assertEquals(
            listOf("192.168.1.9:554", "192.168.1.5:554", "192.168.1.2:554"),
            merged.map { it.camera.endpoint },
        )
    }

    @Test
    fun hosts_inside_a_tier_are_sorted_numerically() {
        val merged = DiscoveryMerge.merge(
            saved = emptyList(),
            onvif = emptyList(),
            openPorts = listOf(cam("192.168.1.100:554"), cam("192.168.1.9:554"), cam("192.168.1.10:554")),
        )

        assertEquals(
            listOf("192.168.1.9:554", "192.168.1.10:554", "192.168.1.100:554"),
            merged.map { it.camera.endpoint },
        )
    }

    @Test
    fun the_same_host_on_two_ports_stays_two_entries() {
        val merged = DiscoveryMerge.merge(
            saved = emptyList(),
            onvif = emptyList(),
            openPorts = listOf(cam("192.168.1.5:554"), cam("192.168.1.5:8554")),
        )

        assertEquals(2, merged.size)
    }

    @Test
    fun an_empty_result_stays_empty() {
        assertEquals(0, DiscoveryMerge.merge(emptyList(), emptyList(), emptyList()).size)
    }

    @Test
    fun the_same_device_with_a_new_ip_is_still_one_camera_and_keeps_its_uuid() {
        val saved = cam("192.168.1.5:554").copy(deviceId = CameraDeviceId.from("3f2504e0-4f89-11d3-9a0c-0305e82c3301"))
        val rescanned = cam("192.168.1.42:554", brand = "EZVIZ")
            .copy(deviceId = CameraDeviceId.from("3F2504E0-4F89-11D3-9A0C-0305E82C3301"))

        val merged = DiscoveryMerge.merge(
            saved = listOf(saved),
            onvif = listOf(rescanned),
            openPorts = emptyList(),
        )

        assertEquals(1, merged.size)
        assertEquals(DiscoverySource.Saved, merged.single().source)
        assertEquals("EZVIZ", merged.single().camera.brand)
        assertEquals(saved.deviceId, merged.single().camera.deviceId)
    }

    @Test
    fun different_devices_on_the_same_endpoint_are_still_distinct() {
        val first = cam("192.168.1.5:554").copy(deviceId = CameraDeviceId.from("3f2504e0-4f89-11d3-9a0c-0305e82c3301"))
        val second = cam("192.168.1.5:554").copy(deviceId = CameraDeviceId.from("9d8e5040-4f89-11d3-9a0c-0305e82c3301"))

        val merged = DiscoveryMerge.merge(emptyList(), listOf(first, second), emptyList())

        assertEquals(2, merged.size)
    }
}
