package id.warung.monitor.feature.auth.domain

/** Monotonic clock seam; production uses `SystemClock.elapsedRealtime`. */
fun interface MonotonicClock {
    fun nowMs(): Long
}