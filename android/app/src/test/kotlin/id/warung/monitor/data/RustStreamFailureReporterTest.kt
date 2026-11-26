package id.warung.monitor.data

import id.warung.monitor.core.Engine
import id.warung.monitor.core.EngineHealth
import id.warung.monitor.core.NoHandle
import id.warung.monitor.core.StreamStatus
import id.warung.monitor.feature.home.domain.StreamErrorKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A preview failure must reach Rust as the matching stream status.
 *
 * `Engine` is a UniFFI object; the binding exposes a `NoHandle` constructor for
 * fakes, so this test overrides only `reportStreamError` and records the status.
 * That keeps the real Kotlin-to-Rust mapping under test without a device.
 */
class RustStreamFailureReporterTest {

    private class RecordingEngine : Engine(NoHandle) {
        val reported = mutableListOf<StreamStatus>()
        var failure: Throwable? = null

        override fun reportStreamError(status: StreamStatus) {
            failure?.let { throw it }
            reported += status
        }

        override fun health(): EngineHealth = throw UnsupportedOperationException()
    }

    private suspend fun report(engine: Engine?, kind: StreamErrorKind) {
        RustStreamFailureReporter { engine }.report(kind)
    }

    @Test
    fun each_preview_failure_maps_to_its_rust_status() = runTest {
        val engine = RecordingEngine()
        val expected = mapOf(
            StreamErrorKind.Unreachable to StreamStatus.UNREACHABLE,
            StreamErrorKind.Timeout to StreamStatus.TIMEOUT,
            StreamErrorKind.AuthFailed to StreamStatus.AUTH_FAILED,
            StreamErrorKind.Protocol to StreamStatus.PROTOCOL,
            StreamErrorKind.Closed to StreamStatus.CLOSED,
            StreamErrorKind.Unknown to StreamStatus.ERROR,
        )
        for (kind in expected.keys) {
            report(engine, kind)
        }

        assertEquals(expected.values.toList(), engine.reported)
    }

    @Test
    fun a_transport_problem_is_not_reported_because_no_stream_was_opened() = runTest {
        val engine = RecordingEngine()

        report(engine, StreamErrorKind.NoNetwork)
        report(engine, StreamErrorKind.WrongNetwork)

        assertEquals("Rust rejects these statuses", emptyList<StreamStatus>(), engine.reported)
    }

    @Test
    fun a_missing_library_or_stopped_engine_is_swallowed() = runTest {
        // `null` models `engineOrNull()` after UnsatisfiedLinkError.
        report(null, StreamErrorKind.Timeout)

        // A stopped engine legitimately throws `NotRunning`; it must not
        // replace or crash the failure the UI already shows.
        val stopped = RecordingEngine().apply { failure = IllegalStateException("NotRunning") }
        report(stopped, StreamErrorKind.Timeout)
        assertEquals(emptyList<StreamStatus>(), stopped.reported)

        val running = RecordingEngine()
        report(running, StreamErrorKind.Timeout)
        assertEquals(listOf(StreamStatus.TIMEOUT), running.reported)
    }
}