package id.warung.monitor.engine

import id.warung.monitor.feature.cameras.session.CountingReason
import id.warung.monitor.feature.cameras.session.CountingState

/**
 * Translates a planner decision into the state Home renders.
 *
 * Pure JVM: no Android types, no FFI, no user-facing text. Keeping the
 * translation outside [RustCameraSession] means every cause the planner can
 * produce is covered by a host test, and adding an enum constant without
 * deciding its reason is a compile error (`docs/kotlin-rust-gaps.md` G-02).
 *
 * Only a successful `Engine.start` may claim [CountingState.Active]; a plan that
 * never reached the engine is never reported as counting.
 */
internal fun EngineStartPlan.Skipped.toCountingState(): CountingState =
    CountingState.Inactive(
        when (reason) {
            EngineStartSkip.Demo -> CountingReason.Demo
            EngineStartSkip.NoTables -> CountingReason.NoTables
        },
    )

/** See [toCountingState] for [EngineStartPlan.Skipped]. */
internal fun EngineStartPlan.Unavailable.toCountingState(): CountingState =
    CountingState.Inactive(
        when (blocker) {
            EngineStartBlocker.ModelMissing -> CountingReason.ModelMissing
            EngineStartBlocker.RuntimeMissing -> CountingReason.RuntimeMissing
            EngineStartBlocker.RamTooLow -> CountingReason.RamTooLow
            EngineStartBlocker.ConfigInvalid -> CountingReason.ConfigInvalid
            // An unnamed cause must still be reported, not swallowed.
            EngineStartBlocker.Unknown -> CountingReason.StartFailed
        },
    )
