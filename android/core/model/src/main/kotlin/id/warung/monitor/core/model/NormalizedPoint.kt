package id.warung.monitor.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A point in normalised frame space. Both axes are finite and inside `0..=1`.
 */
data class NormalizedPoint(val x: Double, val y: Double) {

    init {
        require(x.isFinite() && y.isFinite()) { "Coordinate must be finite" }
        require(x in 0.0..1.0 && y in 0.0..1.0) { "Coordinate must be within 0..1" }
    }

    /** Rounds to the four decimals written to JSON. */
    fun quantized(): NormalizedPoint = NormalizedPoint(x.round4(), y.round4())

    companion object {
        /**
         * Builds a point from raw values, or returns `null` when the value is not
         * finite or outside the frame. This is the decode-side counterpart of the
         * Rust loader, which rejects out-of-range coordinates instead of clamping.
         */
        fun orNull(x: Double, y: Double): NormalizedPoint? {
            if (!x.isFinite() || !y.isFinite()) return null
            if (x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) return null
            return NormalizedPoint(x, y)
        }

        /** Clamps an arbitrary screen-derived value into the frame. */
        fun clamped(x: Double, y: Double): NormalizedPoint =
            NormalizedPoint(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
    }
}

/** JSON serialisation precision used by the v2 contract. */
const val COORDINATE_SCALE: Int = 4

/** Rounds a coordinate to four decimals using HALF_UP. */
fun Double.round4(): Double =
    BigDecimal.valueOf(this).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP).toDouble()