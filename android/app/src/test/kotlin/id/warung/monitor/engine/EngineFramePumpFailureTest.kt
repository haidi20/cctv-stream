package id.warung.monitor.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Failures to deliver a frame must become visible instead of being swallowed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineFramePumpFailureTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun three_failed_push_attempts_report_a_failing_pump() = runTest {
        val pump = EngineFramePump(
            engineProvider = id.warung.monitor.di.EngineProvider(),
            cadence = InferenceCadence(),
            loadSource = object : DeviceLoadSource {
                override fun thermalStatus(): Int? = null
                override fun availableRamMb(): Long? = null
            },
            budget = InferenceBudget(),
        )
        repeat(EngineFramePump.FAILURE_THRESHOLD) {
            pump.noteFrameDeliveryFailure(IllegalStateException("native rejected frame"))
        }

        assertEquals(EngineFramePumpStatus.Failing, pump.state.value.status)
        assertEquals(EngineFramePump.FAILURE_THRESHOLD, pump.state.value.consecutiveFailures)
        assertEquals(
            "a later success clears the failure streak",
            EngineFramePumpStatus.Idle,
            pump.apply { noteFrameDeliverySuccess() }.state.value.status,
        )
        assertEquals(0, pump.state.value.consecutiveFailures)
    }
}
