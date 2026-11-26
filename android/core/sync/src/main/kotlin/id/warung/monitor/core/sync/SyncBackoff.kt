package id.warung.monitor.core.sync

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Retry ladder from `docs/monitoring.md` section 4.3: 30 s, 1 min, 5 min, then
 * 15 min as the ceiling.
 *
 * The ladder is pure so a HostBuilder/worker both agree on it and tests can
 * assert it without sleeping. [next] never returns more than [MAX].
 */
object SyncBackoff {

    val steps: List<Duration> = listOf(30.seconds, 1.minutes, 5.minutes)

    val MAX: Duration = 15.minutes

    /** Zero-based [failureCount]: 0 -> 30 s, 1 -> 1 min, 2 -> 5 min, 3+ -> 15 min. */
    fun delayFor(failureCount: Int): Duration {
        if (failureCount < 0) return steps.first()
        return steps.getOrElse(failureCount) { MAX }
    }
}

