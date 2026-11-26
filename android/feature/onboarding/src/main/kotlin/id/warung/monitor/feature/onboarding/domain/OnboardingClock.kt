package id.warung.monitor.feature.onboarding.domain

/**
 * Wall clock for the persisted diagnostic record.
 *
 * A named seam (rather than a raw `() -> Long`) matches the other clocks in the
 * app, keeps tests readable, and lets Hilt provide it like any other binding.
 */
fun interface OnboardingClock {
    fun nowMillis(): Long
}