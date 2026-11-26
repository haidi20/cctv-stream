package id.warung.monitor.feature.home.domain

/**
 * Reports why the video died to the detection engine.
 *
 * Home owns the video (Media3); Rust owns the tables. Without this call the
 * engine keeps the previous table statuses until its 5 s frame watchdog
 * expires, so a dead camera would still show "3 kosong, 2 terisi". That is an
 * unseen table presented as a known one (AGENTS.md rule 5).
 *
 * Implementations must be best effort, must not block the caller for long, and
 * must never log the camera URL, password, or PIN.
 */
fun interface StreamFailureReporter {
    suspend fun report(kind: StreamErrorKind)
}