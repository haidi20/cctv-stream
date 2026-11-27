package id.warung.monitor.feature.settings.ui

import java.util.Locale

/**
 * Human-readable database size for the storage card.
 *
 * Binary units (1 KB = 1024 B) because that is what Android's own storage
 * screens show, and `Locale.US` so the separator does not flip between a
 * device language and a test run.
 */
object StorageSize {

    fun format(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < KB -> "$safe B"
            safe < MB -> scaled(safe / KB.toDouble(), "KB")
            safe < GB -> scaled(safe / MB.toDouble(), "MB")
            else -> scaled(safe / GB.toDouble(), "GB")
        }
    }

    private fun scaled(value: Double, unit: String): String =
        String.format(Locale.US, "%.1f %s", value, unit)

    private const val KB: Long = 1024L
    private const val MB: Long = KB * 1024
    private const val GB: Long = MB * 1024
}
