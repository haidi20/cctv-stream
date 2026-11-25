package id.warung.monitor.feature.auth.domain

/**
 * Failure thresholds from the app plan: 5 -> 30 s, 10 -> 5 min, 15 -> 30 min.
 *
 * Time is supplied by a monotonic clock (`SystemClock.elapsedRealtime`).
 * Wall-clock changes, including a user setting the date back, must not
 * shorten a lockout.
 */
class LockoutPolicy {
    fun lockMillisFor(failures: Int): Long? = when {
        failures >= 15 -> 30 * 60_000L
        failures >= 10 -> 5 * 60_000L
        failures >= 5 -> 30_000L
        else -> null
    }

    /** Failures still available before the next lockout threshold. */
    fun attemptsLeft(failures: Int): Int {
        val next = THRESHOLDS.firstOrNull { it > failures } ?: (failures + 1)
        return (next - failures).coerceAtLeast(0)
    }

    companion object {
        val THRESHOLDS = listOf(5, 10, 15)
    }
}