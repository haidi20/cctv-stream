package id.warung.monitor.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun an_apk_update_replaces_the_model_it_left_behind() {
        var probed = false

        val plan = EngineAssetPaths.planRefresh(
            storedStamp = "2:1000",
            currentStamp = "3:2000",
            targetUsable = true,
        ) {
            probed = true
            false
        }

        assertEquals(ModelRefresh.Copy, plan)
        assertTrue("a different model must be compared, not assumed stale", probed)
    }

    @Test
    fun a_rerun_without_an_update_reuses_the_model_without_reading_files() {
        var probed = false

        val plan = EngineAssetPaths.planRefresh(
            storedStamp = "3:2000",
            currentStamp = "3:2000",
            targetUsable = true,
        ) {
            probed = true
            true
        }

        assertEquals(ModelRefresh.Reuse, plan)
        assertFalse("steady state must not hash two files every start", probed)
    }

    @Test
    fun an_update_that_ships_the_same_model_only_rewrites_the_stamp() {
        val plan = EngineAssetPaths.planRefresh(
            storedStamp = "2:1000",
            currentStamp = "3:2000",
            targetUsable = true,
        ) { true }

        assertEquals(ModelRefresh.Adopt, plan)
    }

    @Test
    fun a_first_install_copies_the_bundled_model() {
        val plan = EngineAssetPaths.planRefresh(
            storedStamp = null,
            currentStamp = "3:2000",
            targetUsable = false,
        ) { false }

        assertEquals(ModelRefresh.Copy, plan)
    }

    @Test
    fun an_untracked_existing_model_is_adopted_when_the_bytes_match() {
        val plan = EngineAssetPaths.planRefresh(
            storedStamp = null,
            currentStamp = "3:2000",
            targetUsable = true,
        ) { true }

        assertEquals(ModelRefresh.Adopt, plan)
    }

    @Test
    fun an_unknown_install_stamp_never_overwrites_a_working_model() {
        val plan = EngineAssetPaths.planRefresh(
            storedStamp = "3:2000",
            currentStamp = null,
            targetUsable = true,
        ) { false }

        assertEquals(ModelRefresh.Reuse, plan)
    }

    @Test
    fun an_unknown_install_stamp_still_copies_when_nothing_is_installed() {
        val plan = EngineAssetPaths.planRefresh(
            storedStamp = null,
            currentStamp = null,
            targetUsable = false,
        ) { false }

        assertEquals(ModelRefresh.Copy, plan)
    }

    @Test
    fun the_stamp_file_sits_next_to_the_model() {
        assertEquals(
            "${EngineAssetPaths.MODEL_NAME}.stamp",
            EngineAssetPaths.STAMP_NAME,
        )
    }

    @Test
    fun the_model_hash_matches_a_known_digest() {
        val hex = EngineAssetPaths.sha256Hex("abc".byteInputStream())

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex,
        )
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
