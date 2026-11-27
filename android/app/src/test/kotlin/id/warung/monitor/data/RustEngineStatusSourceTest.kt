package id.warung.monitor.data

import id.warung.monitor.core.Engine
import id.warung.monitor.core.EngineHealth
import id.warung.monitor.core.EngineListener
import id.warung.monitor.core.NoHandle
import id.warung.monitor.core.StreamStatus
import id.warung.monitor.feature.home.domain.EnginePhase
import id.warung.monitor.feature.home.domain.StreamErrorKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health snapshot must keep Rust's failure cause. A wrong camera password
 * is not a Wi-Fi problem, so `auth_failed` may never arrive as `Unknown`.
 */
class RustEngineStatusSourceTest {

    private class StubEngine(private val health: EngineHealth) : Engine(NoHandle) {
        override fun health(): EngineHealth = health

        override fun subscribe(listener: EngineListener) = Unit
    }

    private fun health(
        stream: StreamStatus,
        needsRecalibration: Boolean = false,
    ) = EngineHealth(
        cameraId = "cam-1",
        stream = stream,
        framesIn = 12uL,
        framesSampled = 3uL,
        connects = 1u,
        reconnectFailures = 0u,
        lastFrameAgeMs = 40uL,
        tables = 4u,
        tablesAvailable = 1u,
        tablesOccupied = 2u,
        tablesUnknown = 1u,
        uptimeMs = 9_000uL,
        inferenceFps = 2.0,
        lastInferenceAgeMs = 40uL,
        frameToInferenceMs = 90uL,
        rssKb = 184_320uL,
        framesDropped = 1_204uL,
        inferenceP50Ms = 216uL,
        inferenceP95Ms = 276uL,
        needsRecalibration = needsRecalibration,
        modelReady = true,
    )

    private suspend fun read(health: EngineHealth?) =
        RustEngineStatusSource { health?.let { StubEngine(it) } }.read(null)

    @Test
    fun a_rejected_password_keeps_its_own_cause() = runTest {
        val status = read(health(StreamStatus.AUTH_FAILED))

        assertEquals(EnginePhase.Failed, status.phase)
        assertEquals(StreamErrorKind.AuthFailed, status.failure)
        assertFalse(status.failure!!.isNetworkProblem)

        assertTrue(status.available)
    }

    @Test
    fun a_timeout_is_kept_separate_from_an_unreachable_camera() = runTest {
        assertEquals(StreamErrorKind.Timeout, read(health(StreamStatus.TIMEOUT)).failure)
        assertEquals(
            StreamErrorKind.Unreachable,
            read(health(StreamStatus.UNREACHABLE)).failure,
        )
    }

    @Test
    fun a_live_stream_has_no_failure_cause() = runTest {
        val status = read(health(StreamStatus.CONNECTED))

        assertEquals(EnginePhase.Live, status.phase)
        assertNull(status.failure)
    }

    @Test
    fun the_recalibration_flag_is_carried_through() = runTest {
        assertTrue(read(health(StreamStatus.CONNECTED, needsRecalibration = true)).needsRecalibration)
        assertFalse(read(health(StreamStatus.CONNECTED)).needsRecalibration)
    }

    @Test
    fun resource_metrics_come_from_the_rust_snapshot() = runTest {
        val status = read(health(StreamStatus.CONNECTED))

        assertEquals(184_320L, status.rssKb)
        assertEquals(1_204L, status.framesDropped)
        assertEquals(216L, status.inferenceP50Ms)
        assertEquals(276L, status.inferenceP95Ms)
    }

    @Test
    fun an_unavailable_engine_reports_no_resource_numbers() = runTest {
        val status = read(null)

        assertNull(status.rssKb)
        assertNull(status.framesDropped)
        assertNull(status.inferenceP50Ms)
        assertNull(status.inferenceP95Ms)
    }

    @Test
    fun a_missing_library_reports_unavailable_without_a_cause() = runTest {
        val status = read(null)

        assertFalse(status.available)
        assertNull(status.failure)
        assertNull(status.summary)
    }
}
