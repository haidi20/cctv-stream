package id.warung.monitor.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** App-data size is the soak's only proof that the outbox is not growing. */
class DirectoryUsageTest {

    @Test
    fun an_empty_tree_measures_zero_kib() {
        val root = Files.createTempDirectory("dir-usage").toFile()
        try {
            assertEquals(0L, DirectoryUsage.sizeKb(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nested_files_are_summed_and_reported_in_kib() {
        val root = Files.createTempDirectory("dir-usage").toFile()
        try {
            File(root, "a").apply { parentFile?.mkdirs(); writeBytes(ByteArray(1024)) }
            val nested = File(root, "x/y").apply { mkdirs() }
            File(nested, "b").writeBytes(ByteArray(1536))
            assertEquals(2L, DirectoryUsage.sizeKb(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun a_missing_root_reports_null_instead_of_zero() {
        val root = File(Files.createTempDirectory("dir-usage").toFile(), "gone")
        assertNull(DirectoryUsage.sizeKb(root))
        assertTrue("the test must not create the missing path", !root.exists())
    }
}