package id.warung.monitor.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Android 15+ maps native libraries straight out of the APK, so
 * `nativeLibraryDir` is empty on this target. The resolver must then fall back
 * to the bare soname instead of reporting "ONNX Runtime belum tersedia" forever.
 */
class EngineAssetPathsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun an_extracted_library_wins_over_the_soname_fallback() {
        val extracted = temporaryFolder.newFile(EngineAssetPaths.ONNX_RUNTIME_NAME)
        var loaded = false

        val path = EngineAssetPaths.resolveRuntimePath(extracted) { loaded = true }

        assertEquals(extracted.absolutePath, path)
        assertEquals("an extracted file must not be loaded twice", false, loaded)
    }

    @Test
    fun an_empty_native_library_dir_falls_back_to_the_soname() {
        val extracted = File(temporaryFolder.root, EngineAssetPaths.ONNX_RUNTIME_NAME)

        val path = EngineAssetPaths.resolveRuntimePath(extracted) { }

        assertEquals(EngineAssetPaths.ONNX_RUNTIME_NAME, path)
    }

    @Test
    fun a_failed_load_returns_null_instead_of_a_fake_path() {
        val extracted = File(temporaryFolder.root, EngineAssetPaths.ONNX_RUNTIME_NAME)

        val path = EngineAssetPaths.resolveRuntimePath(extracted) {
            throw UnsatisfiedLinkError("no native library for this ABI")
        }

        assertNull("a failed load must not pretend the runtime exists", path)
    }
}