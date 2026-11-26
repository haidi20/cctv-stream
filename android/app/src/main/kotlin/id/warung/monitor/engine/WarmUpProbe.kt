package id.warung.monitor.engine

import id.warung.monitor.core.model.MappingFrame
import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.PolygonValidation
import id.warung.monitor.core.model.PolygonValidator
import id.warung.monitor.core.model.TableConfig
import id.warung.monitor.core.model.TableConfigJson
import id.warung.monitor.core.model.TableConfigSet
import id.warung.monitor.core.model.TableId
import id.warung.monitor.core.model.TableLabel
import id.warung.monitor.feature.onboarding.domain.DeviceErrorCode

/**
 * Pure parts of the native warm-up, kept out of [RustEngineWarmUp] so they can
 * be verified on a host without a device or the shared library.
 *
 * The probe table exercises the same polygon rules and v2 codec the engine
 * enforces at `start`, so a schema drift fails a unit test instead of a device.
 */
object WarmUpProbe {

    /** Matches the supported camera-id alphabet; never shown to the user. */
    const val CAMERA_ID: String = "device-check"
    const val FRAME_SIZE: Int = 64

    private val CORNERS = listOf(
        NormalizedPoint(0.1, 0.1),
        NormalizedPoint(0.9, 0.1),
        NormalizedPoint(0.9, 0.9),
        NormalizedPoint(0.1, 0.9),
    )

    /**
     * One valid table covering the frame; `Engine.start` rejects an empty set.
     */
    fun tablesJson(): String {
        val polygon = when (val result = PolygonValidator.validate(CORNERS)) {
            is PolygonValidation.Valid -> result.polygon
            is PolygonValidation.Invalid -> error("warm-up polygon rejected")
        }
        val config = TableConfigSet(
            frame = MappingFrame(FRAME_SIZE, FRAME_SIZE, "1:1"),
            tables = listOf(
                TableConfig(
                    id = TableId("probe"),
                    label = TableLabel("Pemeriksaan"),
                    polygon = polygon,
                ),
            ),
        )
        return TableConfigJson.encode(config, prettyPrint = false)
    }

    /** Maps a native model failure to the code list in `device-compatibility.md`. */
    fun modelErrorCode(reason: String): String = when {
        reason.contains("checksum", ignoreCase = true) -> DeviceErrorCode.MODEL_CHECKSUM
        reason.contains("not found", ignoreCase = true) ||
            reason.contains("missing", ignoreCase = true) -> DeviceErrorCode.MODEL_MISSING
        else -> DeviceErrorCode.ONNX_INIT
    }
}