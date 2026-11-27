package id.warung.monitor.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The storage card must read like Android's own storage screens: binary units,
 * one decimal, and never a negative size.
 */
class StorageSizeTest {

    @Test
    fun small_databases_report_raw_bytes() {
        assertEquals("0 B", StorageSize.format(0L))
        assertEquals("512 B", StorageSize.format(512L))
    }

    @Test
    fun kilobytes_and_megabytes_keep_one_decimal() {
        assertEquals("2.0 KB", StorageSize.format(2_048L))
        assertEquals("1.5 MB", StorageSize.format(1_572_864L))
    }

    @Test
    fun a_gigabyte_sized_database_is_reported_in_gb() {
        assertEquals("1.0 GB", StorageSize.format(1_073_741_824L))
    }

    @Test
    fun a_negative_size_is_clamped_instead_of_shown() {
        assertEquals("0 B", StorageSize.format(-5L))
    }
}
