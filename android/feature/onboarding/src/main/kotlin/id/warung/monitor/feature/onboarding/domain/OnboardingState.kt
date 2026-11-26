package id.warung.monitor.feature.onboarding.domain

/**
 * Screen state for the pre-PIN diagnostic.
 *
 * `Approved` is state, not a one-shot event, so a configuration change or a
 * late collector still sees the decision. It is only ever produced for a
 * non-blocked profile.
 */
sealed interface OnboardingState {
    /** Reading the persisted record; nothing is drawn yet. */
    data object Loading : OnboardingState

    /** No current record: the user must run the check. */
    data object Check : OnboardingState

    data object Checking : OnboardingState

    /** Warning profile: the user may continue with the lower profile. */
    data class Ready(val report: DeviceReport) : OnboardingState

    /** Fatal profile: Home, the service, and RTSP stay closed. */
    data class Blocked(val report: DeviceReport) : OnboardingState

    /** The gate is open; the shell may show PIN creation or login. */
    data class Approved(val profile: DeviceProfile) : OnboardingState
}