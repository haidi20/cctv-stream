package id.warung.monitor.feature.onboarding.domain

import id.warung.monitor.feature.onboarding.data.InMemoryOnboardingStore
import id.warung.monitor.feature.onboarding.data.OnboardingRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun capability(
        apiLevel: Int = 33,
        abis: List<String> = listOf("arm64-v8a"),
        ramTotalMb: Int = 4_096,
        storageFreeMb: Int = 4_096,
        modelBundled: Boolean = true,
        nativeLoadable: Boolean = true,
    ) = DeviceCapability(
        apiLevel = apiLevel,
        abis = abis,
        ramTotalMb = ramTotalMb,
        ramAvailableMb = 1_200,
        storageFreeMb = storageFreeMb,
        modelBundled = modelBundled,
        nativeLibraryLoadable = nativeLoadable,
        notificationPermissionGranted = true,
        wifiConnected = true,
        appVersionCode = VERSION_CODE,
        appVersionName = "0.1.0",
    )

    private fun model(
        capability: DeviceCapability = capability(),
        warmUp: WarmUpResult = WarmUpResult.Passed(100),
        store: InMemoryOnboardingStore = InMemoryOnboardingStore(),
        versionCode: Long = VERSION_CODE,
    ) = OnboardingViewModel(
        source = object : DeviceCapabilitySource {
            override suspend fun currentVersionCode(): Long = versionCode
            override suspend fun read(): DeviceCapability = capability
        },
        warmUp = { warmUp },
        store = store,
        clock = OnboardingClock { 1_000L },
    )

    @Test
    fun first_launch_waits_for_the_user_to_run_the_check() = runTest(dispatcher) {
        val model = model()
        testScheduler.runCurrent()

        assertTrue(model.state.value is OnboardingState.Check)
    }

    @Test
    fun passing_device_is_approved_without_an_extra_prompt() = runTest(dispatcher) {
        val model = model()
        testScheduler.runCurrent()

        model.runCheck()
        testScheduler.runCurrent()

        val approved = model.state.value as OnboardingState.Approved
        assertEquals(DeviceProfile.Full, approved.profile)
    }

    @Test
    fun warning_device_requires_explicit_continue() = runTest(dispatcher) {
        val model = model(capability = capability(ramTotalMb = 3_072))
        testScheduler.runCurrent()

        model.runCheck()
        testScheduler.runCurrent()

        val ready = model.state.value as OnboardingState.Ready
        assertEquals(DeviceProfile.Balanced, ready.report.profile)

        model.continueWithLowerProfile()
        assertEquals(DeviceProfile.Balanced, (model.state.value as OnboardingState.Approved).profile)
    }

    @Test
    fun blocked_device_never_becomes_approved() = runTest(dispatcher) {
        val model = model(capability = capability(abis = listOf("armeabi-v7a")))
        testScheduler.runCurrent()

        model.runCheck()
        testScheduler.runCurrent()

        assertTrue(model.state.value is OnboardingState.Blocked)

        model.continueWithLowerProfile()
        assertTrue(model.state.value is OnboardingState.Blocked)
    }

    @Test
    fun a_second_run_while_checking_is_ignored() = runTest(dispatcher) {
        var reads = 0
        val model = OnboardingViewModel(
            source = object : DeviceCapabilitySource {
                override suspend fun currentVersionCode(): Long = VERSION_CODE
                override suspend fun read(): DeviceCapability {
                    reads += 1
                    return capability()
                }
            },
            warmUp = { WarmUpResult.Passed(1) },
            store = InMemoryOnboardingStore(),
            clock = OnboardingClock { 0L },
        )
        testScheduler.runCurrent()

        model.runCheck()
        model.runCheck()
        testScheduler.runCurrent()

        assertEquals(1, reads)
    }

    @Test
    fun a_saved_record_for_the_same_version_skips_the_screen() = runTest(dispatcher) {
        val store = InMemoryOnboardingStore(
            OnboardingRecord(
                versionCode = VERSION_CODE,
                profile = DeviceProfile.Lite,
                engineReady = false,
                checkedAtMillis = 1L,
            ),
        )
        val model = model(store = store)
        testScheduler.runCurrent()

        assertEquals(DeviceProfile.Lite, (model.state.value as OnboardingState.Approved).profile)
    }

    @Test
    fun a_record_from_an_older_version_forces_a_new_check() = runTest(dispatcher) {
        val store = InMemoryOnboardingStore(
            OnboardingRecord(
                versionCode = VERSION_CODE - 1,
                profile = DeviceProfile.Full,
                engineReady = true,
                checkedAtMillis = 1L,
            ),
        )
        val model = model(store = store)
        testScheduler.runCurrent()

        assertTrue(model.state.value is OnboardingState.Check)
    }

    @Test
    fun retry_from_blocked_runs_the_check_again() = runTest(dispatcher) {
        val store = InMemoryOnboardingStore()
        val model = model(
            capability = capability(storageFreeMb = 128),
            store = store,
        )
        testScheduler.runCurrent()
        model.runCheck()
        testScheduler.runCurrent()
        assertTrue(model.state.value is OnboardingState.Blocked)

        model.retry()
        testScheduler.runCurrent()

        assertTrue(model.state.value is OnboardingState.Blocked)
        assertTrue(store.saved.isNotEmpty())
    }

    @Test
    fun result_is_persisted_with_the_running_version() = runTest(dispatcher) {
        val store = InMemoryOnboardingStore()
        val model = model(store = store)
        testScheduler.runCurrent()

        model.runCheck()
        testScheduler.runCurrent()

        val saved = store.saved.single()
        assertEquals(VERSION_CODE, saved.versionCode)
        assertEquals(DeviceProfile.Full, saved.profile)
        assertEquals(1_000L, saved.checkedAtMillis)
    }

    @Test
    fun warm_up_is_not_requested_for_a_blocked_platform() = runTest(dispatcher) {
        var warmUps = 0
        val model = OnboardingViewModel(
            source = object : DeviceCapabilitySource {
                override suspend fun currentVersionCode(): Long = VERSION_CODE
                override suspend fun read(): DeviceCapability = capability(apiLevel = 28)
            },
            warmUp = {
                warmUps += 1
                WarmUpResult.Passed(1)
            },
            store = InMemoryOnboardingStore(),
            clock = OnboardingClock { 0L },
        )
        testScheduler.runCurrent()

        model.runCheck()
        testScheduler.runCurrent()

        assertEquals(0, warmUps)
        assertTrue(model.state.value is OnboardingState.Blocked)
    }

    private companion object {
        const val VERSION_CODE = 7L
    }
}