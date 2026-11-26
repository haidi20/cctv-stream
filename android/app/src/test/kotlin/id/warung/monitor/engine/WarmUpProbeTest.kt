package id.warung.monitor.engine

import id.warung.monitor.core.model.TableConfigDecodeResult
import id.warung.monitor.core.model.TableConfigJson
import id.warung.monitor.feature.onboarding.domain.DeviceErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-level contract for the native warm-up: the probe table must pass the
 * same v2 codec the engine applies at `start`, and a missing model must never
 * be reported as a generic success.
 */
class WarmUpProbeTest {

    @Test
    fun the_probe_table_round_trips_through_the_engine_codec() {
        val decoded = TableConfigJson.decode(WarmUpProbe.tablesJson())

        assertTrue(decoded is TableConfigDecodeResult.Success)
        val set = (decoded as TableConfigDecodeResult.Success).config
        assertEquals(1, set.tables.size)
        assertEquals(64, set.frame.width)
        assertEquals(64, set.frame.height)
    }

    @Test
    fun the_probe_camera_id_is_accepted_by_the_ffi_alphabet() {
        assertTrue(WarmUpProbe.CAMERA_ID.matches(Regex("[A-Za-z0-9._-]{1,64}")))
    }

    @Test
    fun a_missing_model_maps_to_the_missing_code() {
        assertEquals(
            DeviceErrorCode.MODEL_MISSING,
            WarmUpProbe.modelErrorCode("model file not found at /data/app"),
        )
        assertEquals(
            DeviceErrorCode.MODEL_MISSING,
            WarmUpProbe.modelErrorCode("Model asset missing"),
        )
    }

    @Test
    fun a_checksum_mismatch_is_not_reported_as_missing() {
        assertEquals(
            DeviceErrorCode.MODEL_CHECKSUM,
            WarmUpProbe.modelErrorCode("checksum mismatch for model"),
        )
    }

    @Test
    fun an_unknown_native_failure_falls_back_to_onnx_init() {
        assertEquals(
            DeviceErrorCode.ONNX_INIT,
            WarmUpProbe.modelErrorCode("failed to create session"),
        )
    }
}