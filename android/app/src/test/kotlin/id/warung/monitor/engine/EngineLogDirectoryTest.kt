package id.warung.monitor.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `core-ffi::observability` fails hard when `CCTV_LOG_DIR` points at a path the
 * app cannot write, and its fallback (`std::env::temp_dir()`) is `/tmp` on
 * Android. These tests pin the Kotlin half of that contract: create an
 * app-private directory first, then export exactly that path.
 */
class EngineLogDirectoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun logDirectory(): File = File(temporaryFolder.root, EngineLogDirectory.DIR_NAME)

    @Test
    fun it_creates_the_log_directory_and_exports_it() {
        val directory = logDirectory()
        val environment = RecordingEnvironment()

        val path = EngineLogDirectory(environment, directory).prepare()

        assertEquals(directory.absolutePath, path)
        assertTrue("direktori log harus benar-benar ada", directory.isDirectory)
        assertEquals(directory.absolutePath, environment.values[EngineLogDirectory.ENV_VAR])
    }

    @Test
    fun it_creates_the_missing_parent_directories() {
        val directory = File(temporaryFolder.root, "nested/logs")
        val environment = RecordingEnvironment()

        assertEquals(directory.absolutePath, EngineLogDirectory(environment, directory).prepare())
        assertTrue(directory.isDirectory)
    }

    @Test
    fun a_directory_that_is_already_there_is_reused_without_duplicating_the_variable() {
        val directory = logDirectory().apply { mkdirs() }
        val environment = RecordingEnvironment()
        val logDirectory = EngineLogDirectory(environment, directory)

        assertEquals(directory.absolutePath, logDirectory.prepare())
        assertEquals(directory.absolutePath, logDirectory.prepare())

        assertEquals(1, environment.values.size)
    }

    @Test
    fun a_path_blocked_by_a_file_exports_nothing() {
        val blocked = temporaryFolder.newFile(EngineLogDirectory.DIR_NAME)
        val environment = RecordingEnvironment()

        val path = EngineLogDirectory(environment, blocked).prepare()

        assertNull(path)
        assertTrue("variabel tidak boleh menunjuk path yang tidak bisa ditulis", environment.values.isEmpty())
    }

    @Test
    fun a_rejected_variable_is_reported_as_null() {
        val directory = logDirectory()
        val environment = RecordingEnvironment(refuse = true)

        assertNull(EngineLogDirectory(environment, directory).prepare())
    }

    @Test
    fun the_variable_name_matches_what_core_ffi_reads() {
        assertEquals("CCTV_LOG_DIR", EngineLogDirectory.ENV_VAR)
        assertEquals("logs", EngineLogDirectory.DIR_NAME)
    }

    private class RecordingEnvironment(private val refuse: Boolean = false) : ProcessEnvironment {

        val values = mutableMapOf<String, String>()

        override fun set(name: String, value: String): Boolean {
            if (refuse) return false
            values[name] = value
            return true
        }
    }
}
