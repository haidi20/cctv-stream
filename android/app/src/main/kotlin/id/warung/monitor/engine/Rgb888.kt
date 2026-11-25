package id.warung.monitor.engine

import android.graphics.Bitmap

/** Packed RGB888 conversion used by the FFI frame contract. */
object Rgb888 {

    /** Packs ARGB pixels into RGB888; alpha is discarded. */
    fun pack(argb: IntArray): ByteArray {
        val rgb = ByteArray(argb.size * 3)
        argb.forEachIndexed { index, pixel ->
            val offset = index * 3
            rgb[offset] = ((pixel shr 16) and 0xFF).toByte()
            rgb[offset + 1] = ((pixel shr 8) and 0xFF).toByte()
            rgb[offset + 2] = (pixel and 0xFF).toByte()
        }
        return rgb
    }

    /** Reads every pixel of [bitmap] and returns packed RGB888. */
    fun fromBitmap(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pack(pixels)
    }
}