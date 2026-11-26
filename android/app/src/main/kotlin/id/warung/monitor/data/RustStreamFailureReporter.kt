package id.warung.monitor.data

import id.warung.monitor.core.Engine
import id.warung.monitor.core.StreamStatus
import id.warung.monitor.di.EngineProvider
import id.warung.monitor.feature.home.domain.StreamErrorKind
import id.warung.monitor.feature.home.domain.StreamFailureReporter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridges a preview failure to Rust's `report_stream_error`.
 *
 * Rust owns the table statuses but cannot see the Media3 player, so this is
 * the only way it learns that the video died before its own frame watchdog
 * fires. Without it the cards would keep the last counts for up to 5 seconds
 * and a dead camera would look like a room with tables.
 *
 * A missing `libcore_ffi.so`, a stopped engine (`NotRunning`), or a rejected
 * status is swallowed: the UI already shows the failure, and reporting must
 * never mask it. Nothing here logs the camera URL or credentials.
 */
class RustStreamFailureReporter(
    private val engine: () -> Engine?,
) : StreamFailureReporter {

    @Inject
    constructor(engineProvider: EngineProvider) : this(engineProvider::engineOrNull)

    override suspend fun report(kind: StreamErrorKind) = withContext(Dispatchers.IO) {
        val status = kind.toStreamStatus() ?: return@withContext
        val target = engine() ?: return@withContext
        try {
            target.reportStreamError(status)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Stopped engine or missing library: the failure is already shown.
        }
        Unit
    }

    private fun StreamErrorKind.toStreamStatus(): StreamStatus? = when (this) {
        StreamErrorKind.Unreachable -> StreamStatus.UNREACHABLE
        StreamErrorKind.Timeout -> StreamStatus.TIMEOUT
        StreamErrorKind.AuthFailed -> StreamStatus.AUTH_FAILED
        StreamErrorKind.Protocol -> StreamStatus.PROTOCOL
        StreamErrorKind.Closed -> StreamStatus.CLOSED
        StreamErrorKind.Unknown -> StreamStatus.ERROR
        // Transport problems never reached the camera, so there is no stream
        // to tear down; Rust rejects `Connected`/`Idle` for the same reason.
        StreamErrorKind.NoNetwork, StreamErrorKind.WrongNetwork -> null
    }
}