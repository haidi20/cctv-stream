package id.warung.monitor.feature.onboarding.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.warung.monitor.feature.onboarding.data.OnboardingRecord
import id.warung.monitor.feature.onboarding.data.OnboardingStore
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the pre-PIN diagnostic.
 *
 * A current record from the same APK version skips the screen. A running check
 * cannot be started twice. A `blocked` profile is never approved, even after a
 * retry: only a re-check that now passes can open Home.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val source: DeviceCapabilitySource,
    private val warmUp: EngineWarmUp,
    private val store: OnboardingStore,
    private val clock: OnboardingClock,
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Loading)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private var checking = false

    init {
        viewModelScope.launch { load() }
    }

    /**
     * A record is current only for the version that wrote it. Blocked records
     * are not trusted across launches: they intentionally force a re-check.
     */
    private suspend fun load() {
        val currentVersion = runCatching { source.currentVersionCode() }.getOrNull()
        val record = runCatching { store.load() }.getOrNull()
        _state.value = when {
            currentVersion == null -> OnboardingState.Check
            record == null -> OnboardingState.Check
            record.versionCode != currentVersion -> OnboardingState.Check
            record.profile == DeviceProfile.Blocked -> OnboardingState.Check
            else -> OnboardingState.Approved(record.profile)
        }
    }

    fun runCheck() {
        if (checking) return
        if (_state.value !is OnboardingState.Check && _state.value !is OnboardingState.Ready) {
            return
        }
        checking = true
        _state.value = OnboardingState.Checking
        viewModelScope.launch {
            val report = try {
                evaluate()
            } catch (error: CancellationException) {
                checking = false
                throw error
            } catch (_: Throwable) {
                checking = false
                _state.value = OnboardingState.Check
                return@launch
            }
            checking = false
            persist(report)
            _state.value = when {
                report.profile == DeviceProfile.Blocked -> OnboardingState.Blocked(report)
                report.hasWarning -> OnboardingState.Ready(report)
                else -> OnboardingState.Approved(report.profile)
            }
        }
    }

    /** Continues with the lower profile shown on a warning screen. */
    fun continueWithLowerProfile() {
        val ready = _state.value as? OnboardingState.Ready ?: return
        if (ready.report.profile == DeviceProfile.Blocked) return
        _state.value = OnboardingState.Approved(ready.report.profile)
    }

    /** Re-runs the check from a warning or blocked screen. */
    fun retry() {
        if (checking) return
        val current = _state.value
        if (current is OnboardingState.Blocked || current is OnboardingState.Ready) {
            _state.value = OnboardingState.Check
            runCheck()
        }
    }

    private suspend fun evaluate(): DeviceReport {
        val capability = source.read()
        val warmUpResult = if (
            capability.apiLevel >= DeviceAssessment.MIN_API &&
            capability.isArm64 &&
            capability.nativeLibraryLoadable &&
            capability.modelBundled
        ) {
            warmUp.warmUp()
        } else {
            WarmUpResult.Skipped
        }
        return DeviceAssessment.assess(capability, warmUpResult)
    }

    private suspend fun persist(report: DeviceReport) {
        runCatching {
            store.save(
                OnboardingRecord(
                    versionCode = report.appVersionCode,
                    profile = report.profile,
                    engineReady = report.engineReady,
                    checkedAtMillis = clock.nowMillis(),
                ),
            )
        }
    }
}