package id.warung.monitor.data

import id.warung.monitor.core.Engine
import id.warung.monitor.core.EngineHealth
import id.warung.monitor.core.EngineListener
import id.warung.monitor.core.NoHandle
import id.warung.monitor.core.StreamStatus
import id.warung.monitor.feature.home.domain.AnalysisScope
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

    /** Counts FFI reads so the cache can be proven instead of assumed. */
    private class CountingEngine(private val health: EngineHealth) : Engine(NoHandle) {
        var healthCalls = 0

        override fun health(): EngineHealth {
            healthCalls += 1
            return health
        }

        override fun subscribe(listener: EngineListener) = Unit
    }

    /** Boxes as Rust serves them, with the age measured on the session clock. */
    private class BoxEngine(private val boxes: List<id.warung.monitor.core.DetectionBox>) :
        Engine(NoHandle) {
        override fun lastDetections(): List<id.warung.monitor.core.DetectionBox> = boxes

        override fun subscribe(listener: EngineListener) = Unit
    }

    /** Boxes plus the scope Rust derived from the mapped polygons. */
    private class ScopedEngine(
        private val boxes: List<id.warung.monitor.core.DetectionBox>,
        private val region: id.warung.monitor.core.AnalysisRegion?,
        private val failRegion: Boolean = false,
    ) : Engine(NoHandle) {
        override fun lastDetections(): List<id.warung.monitor.core.DetectionBox> = boxes

        override fun analysisRegion(): id.warung.monitor.core.AnalysisRegion? {
            if (failRegion) throw IllegalStateException("scope read failed")
            return region
        }

        override fun subscribe(listener: EngineListener) = Unit
    }

    private fun box(inRegion: Boolean) = id.warung.monitor.core.DetectionBox(
        x = 0.1f,
        y = 0.2f,
        width = 0.3f,
        height = 0.4f,
        confidence = 0.9f,
        frameSeq = 12uL,
        capturedAtMs = 1_000uL,
        ageMs = 200uL,
        stale = false,
        inRegion = inRegion,
    )

    private fun health(
        stream: StreamStatus,
        needsRecalibration: Boolean = false,
        viewMoved: Boolean = false,
        viewChangedFraction: Float = 0f,
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
        viewMoved = viewMoved,
        viewChangedFraction = viewChangedFraction,
        modelReady = true,
    )

    private suspend fun read(health: EngineHealth?) =
        RustEngineStatusSource(engine = { health?.let { StubEngine(it) } }).read(null)

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
    fun the_box_age_comes_from_rust_unchanged() = runTest {
        val box = id.warung.monitor.core.DetectionBox(
            x = 0.1f,
            y = 0.2f,
            width = 0.3f,
            height = 0.4f,
            confidence = 0.9f,
            frameSeq = 12uL,
            capturedAtMs = 1_000uL,
            ageMs = 850uL,
            stale = false,
            inRegion = true,
        )
        val source = RustEngineStatusSource(engine = { BoxEngine(listOf(box)) })

        val boxes = source.readDetections(null)

        assertEquals(1, boxes?.size)
        assertEquals(
            "the overlay decides staleness from this number, so it must not be re-stamped",
            850L,
            boxes!!.first().ageMillis,
        )
    }

    @Test
    fun the_scope_verdict_of_each_box_is_carried_through() = runTest {
        val source = RustEngineStatusSource(
            engine = { ScopedEngine(listOf(box(inRegion = true), box(inRegion = false)), null) },
        )

        val boxes = source.readDetections(null)!!

        assertEquals(listOf(true, false), boxes.map { it.inRegion })
    }

    @Test
    fun the_scope_rust_derived_from_the_map_reaches_the_overlay() = runTest {
        val region = id.warung.monitor.core.AnalysisRegion(
            x = 0.05f,
            y = 0.25f,
            width = 0.6f,
            height = 0.5f,
        )
        val source = RustEngineStatusSource(
            engine = { ScopedEngine(listOf(box(inRegion = true)), region) },
        )

        val analysis = source.readAnalysis(null)!!

        assertEquals(
            AnalysisScope(x = 0.05f, y = 0.25f, width = 0.6f, height = 0.5f),
            analysis.scopeRegion,
        )
    }

    @Test
    fun a_map_with_no_scope_reports_no_rectangle() = runTest {
        val source = RustEngineStatusSource(
            engine = { ScopedEngine(listOf(box(inRegion = true)), null) },
        )

        assertNull(
            "no mapped table must not become a whole-frame scope",
            source.readAnalysis(null)!!.scopeRegion,
        )
    }

    @Test
    fun an_empty_scope_rectangle_is_not_forwarded_to_the_overlay() = runTest {
        val region = id.warung.monitor.core.AnalysisRegion(
            x = 0.3f,
            y = 0.3f,
            width = 0f,
            height = 0.4f,
        )
        val source = RustEngineStatusSource(
            engine = { ScopedEngine(listOf(box(inRegion = true)), region) },
        )

        assertNull(
            "a zero-width rectangle would draw a hairline, not a scope",
            source.readAnalysis(null)!!.scopeRegion,
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
    fun a_moved_camera_also_asks_for_a_new_map() = runTest {
        // Rust reports the panned camera through its own flag; the banner must
        // light up even if only that flag is set.
        assertTrue(read(health(StreamStatus.CONNECTED, viewMoved = true)).needsRecalibration)
    }

    @Test
    fun a_moved_camera_reports_how_much_of_the_view_changed() = runTest {
        val status = read(
            health(
                StreamStatus.CONNECTED,
                needsRecalibration = true,
                viewMoved = true,
                viewChangedFraction = 0.72f,
            ),
        )

        assertEquals(0.72f, status.viewChangedFraction)
    }

    @Test
    fun a_view_that_never_changed_reports_no_fraction() = runTest {
        assertNull(read(health(StreamStatus.CONNECTED)).viewChangedFraction)
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

    @Test
    fun three_reads_inside_one_second_share_a_single_snapshot() = runTest {
        val engine = CountingEngine(health(StreamStatus.CONNECTED))
        var clock = 0L
        val source = RustEngineStatusSource({ engine }, nowMillis = { clock })

        // One Home poll: the status read and the analysis read happen together.
        source.read(null)
        source.readAnalysis(null)
        clock += 400L
        source.read(null)
        clock += 400L
        source.readAnalysis(null)

        assertEquals(
            "three consumers inside one second must not re-enter the Rust lock",
            1,
            engine.healthCalls,
        )
    }

    @Test
    fun a_snapshot_older_than_the_ttl_is_read_again() = runTest {
        val engine = CountingEngine(health(StreamStatus.CONNECTED))
        var clock = 0L
        val source = RustEngineStatusSource({ engine }, nowMillis = { clock })

        source.read(null)
        clock += RustEngineStatusSource.SNAPSHOT_TTL_MS
        source.read(null)

        assertEquals("a stale snapshot must not hide a new status", 2, engine.healthCalls)
    }

    @Test
    fun a_new_engine_never_reuses_the_previous_snapshot() = runTest {
        val first = CountingEngine(health(StreamStatus.CONNECTED))
        val second = CountingEngine(health(StreamStatus.TIMEOUT))
        var current: Engine = first
        val source = RustEngineStatusSource({ current })

        assertEquals(EnginePhase.Live, source.read(null).phase)
        current = second
        val afterRestart = source.read(null)

        assertEquals("a restarted session must not inherit the old camera", EnginePhase.Failed, afterRestart.phase)
        assertEquals(1, second.healthCalls)
    }
}
