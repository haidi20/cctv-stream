package id.warung.monitor.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class Rgb888Test {

    @Test
    fun packs_argb_pixels_as_rgb_bytes_in_order() {
        val argb = intArrayOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
        )
        assertArrayEquals(
            byteArrayOf(
                0xFF.toByte(), 0x00, 0x00,
                0x00, 0xFF.toByte(), 0x00,
                0x00, 0x00, 0xFF.toByte(),
            ),
            Rgb888.pack(argb),
        )
    }

    @Test
    fun alpha_does_not_leak_into_the_frame_payload() {
        val argb = intArrayOf(0x00123456)
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56),
            Rgb888.pack(argb),
        )
    }
}