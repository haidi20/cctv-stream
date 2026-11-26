package id.warung.monitor.engine

import android.graphics.Bitmap

/** Packed RGB888 conversion used by the FFI frame contract. */
object Rgb888 {

    /** Packs ARGB pixels into RGB888; alpha is discarded. */
    fun pack(argb: IntArray): ByteArray {
        val rgb = ByteArray(argb.size * 3)
        packInto(argb, rgb)
        return rgb
    }

    /**
     * Packs [argb] into an existing [rgb] buffer.
     *
     * Reusing the destination keeps steady-state capture allocation-free; the
     * buffer must hold exactly `argb.size * 3` bytes.
     */
    fun packInto(argb: IntArray, rgb: ByteArray) {
        require(rgb.size == argb.size * 3) {
            "RGB buffer must hold ${argb.size * 3} bytes, got ${rgb.size}"
        }
        var offset = 0
        for (pixel in argb) {
            rgb[offset] = ((pixel shr 16) and 0xFF).toByte()
            rgb[offset + 1] = ((pixel shr 8) and 0xFF).toByte()
            rgb[offset + 2] = (pixel and 0xFF).toByte()
            offset += 3
        }
    }

    /** Reads every pixel of [bitmap] and returns packed RGB888. */
    fun fromBitmap(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pack(pixels)
    }
}