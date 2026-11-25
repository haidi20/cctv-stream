package id.warung.monitor.core.model

/**
 * Reference frame recorded with a table configuration.
 *
 * The pixel size is only a display hint; all geometry uses normalised
 * coordinates. [aspectRatio] is the calibration guard from ADR 0008.
 */
data class MappingFrame(
    val width: Int,
    val height: Int,
    val aspectRatio: String,
) {
    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(parseAspectRatio(aspectRatio) != null) { "Invalid aspect ratio: $aspectRatio" }
    }

    /** Aspect ratio as a single floating point number, for comparisons only. */
    val ratio: Double get() = width.toDouble() / height.toDouble()

    /**
     * True when the configured ratio is an exact multiple of the frame.
     *
     * Uses cross-multiplication, matching `AspectRatio::matches_frame` in Rust, so
     * anamorphic resolutions such as 854x480 are not silently accepted.
     */
    fun matchesAspectRatio(frameWidth: Int, frameHeight: Int): Boolean {
        val parsed = parseAspectRatio(aspectRatio) ?: return false
        if (frameWidth <= 0 || frameHeight <= 0) return false
        return parsed.numerator.toLong() * frameHeight == parsed.denominator.toLong() * frameWidth
    }

    companion object {
        /** Builds a frame from live stream dimensions, keeping the reduced ratio. */
        fun of(width: Int, height: Int, aspectRatio: String): MappingFrame =
            MappingFrame(width, height, aspectRatio)

        /** Reduces a pixel size to its canonical `W:H` form (e.g. 1280x720 -> 16:9). */
        fun canonicalAspectRatio(width: Int, height: Int): String {
            require(width > 0 && height > 0) { "Frame dimensions must be positive" }
            val divisor = gcd(width, height)
            return "${width / divisor}:${height / divisor}"
        }

        private fun gcd(a: Int, b: Int): Int {
            var x = a
            var y = b
            while (y != 0) {
                val tmp = y
                y = x % y
                x = tmp
            }
            return x
        }
    }
}

/** Parsed `W:H` aspect ratio. */
data class ParsedAspectRatio(val numerator: Int, val denominator: Int)

/** Parses `16:9`. Returns `null` for malformed or zero components. */
fun parseAspectRatio(value: String): ParsedAspectRatio? {
    val parts = value.split(':')
    if (parts.size != 2) return null
    val numerator = parts[0].trim().toIntOrNull() ?: return null
    val denominator = parts[1].trim().toIntOrNull() ?: return null
    if (numerator <= 0 || denominator <= 0) return null
    return ParsedAspectRatio(numerator, denominator)
}

/** True when [configured] no longer matches the live frame size. */
fun needsRecalibration(configured: MappingFrame, liveWidth: Int, liveHeight: Int): Boolean =
    !configured.matchesAspectRatio(liveWidth, liveHeight)