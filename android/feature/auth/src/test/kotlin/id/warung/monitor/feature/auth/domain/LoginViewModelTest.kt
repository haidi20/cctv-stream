package id.warung.monitor.feature.auth.domain

import id.warung.monitor.feature.auth.data.InMemoryAuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val hasher = FakeHasher()
    private val policy = LockoutPolicy()
    private var erased = false

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(store: InMemoryAuthStore) = LoginViewModel(
        store = store,
        hasher = hasher,
        policy = policy,
        clock = { BASE_NOW_MS + testScheduler.currentTime },
        dispatcher = dispatcher,
        eraser = object : LocalDataEraser {
            override suspend fun eraseAll() {
                erased = true
            }
        },
    )

    private fun credential(pin: String): PinCredential {
        val salt = ByteArray(16) { it.toByte() }
        return PinCredential(salt, pin.encodeToByteArray(), hasher.iterations)
    }

    private fun LoginViewModel.type(pin: String) {
        pin.forEach(::appendDigit)
    }

    @Test
    fun six_digits_trigger_verification_exactly_once() = runTest(dispatcher) {
        val model = viewModel(InMemoryAuthStore(credential("246810")))
        testScheduler.runCurrent()

        model.type("246810")
        testScheduler.runCurrent()

        assertEquals(1, hasher.verifyCalls)
        assertTrue(model.state.value is LoginState.EnterPin)
    }

    @Test
    fun wrong_pin_decrements_attempts() = runTest(dispatcher) {
        val model = viewModel(InMemoryAuthStore(credential("246810")))
        testScheduler.runCurrent()

        model.type("111111")
        testScheduler.runCurrent()

        val state = model.state.value as LoginState.EnterPin
        assertEquals(4, state.attemptsLeft)
        assertTrue(state.message?.contains("4") == true)
    }

    @Test
    fun five_failures_lock_for_30_seconds() = runTest(dispatcher) {
        val store = InMemoryAuthStore(credential("246810"))
        val model = viewModel(store)
        testScheduler.runCurrent()

        repeat(5) {
            model.type("111111")
            testScheduler.runCurrent()
        }

        val state = model.state.value as LoginState.LockedOut
        assertTrue(state.remainingMs in 29_000..30_000)
        assertEquals(
            BASE_NOW_MS + testScheduler.currentTime + 30_000L,
            store.lockedUntilElapsedMs(),
        )
    }

    @Test
    fun lockout_uses_elapsed_realtime_not_wall_clock() = runTest(dispatcher) {
        val model = viewModel(InMemoryAuthStore(credential("246810")))
        testScheduler.runCurrent()
        repeat(5) {
            model.type("111111")
            testScheduler.runCurrent()
        }
        val before = (model.state.value as LoginState.LockedOut).remainingMs

        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        val after = (model.state.value as LoginState.LockedOut).remainingMs

        assertTrue("remaining should shrink: $before -> $after", after < before)
        assertEquals(20_000L, after)
    }

    @Test
    fun successful_unlock_resets_attempts_and_emits_unlock() = runTest(dispatcher) {
        val store = InMemoryAuthStore(credential("246810"), failures = 3)
        val model = viewModel(store)
        testScheduler.runCurrent()
        var unlocks = 0
        val collector = backgroundScope.launch { model.unlocked.collect { unlocks++ } }
        testScheduler.runCurrent()

        model.type("246810")
        testScheduler.runCurrent()

        assertEquals(0, store.failures())
        assertTrue(model.state.value is LoginState.EnterPin)
        assertEquals(1, unlocks)
        collector.cancel()
    }

    @Test
    fun pin_never_appears_in_state_to_string() = runTest(dispatcher) {
        val model = viewModel(InMemoryAuthStore(credential("246810")))
        testScheduler.runCurrent()

        model.type("24681")
        assertFalse(model.state.value.toString().contains("24681"))
        model.appendDigit('0')
        testScheduler.runCurrent()
        assertFalse(model.state.value.toString().contains("246810"))
    }

    @Test
    fun create_pin_requires_matching_confirmation() = runTest(dispatcher) {
        val store = InMemoryAuthStore()
        val model = viewModel(store)
        testScheduler.runCurrent()

        model.type("135790")
        model.type("135791")
        testScheduler.runCurrent()

        assertNull(store.credential())
        val state = model.state.value as LoginState.CreatePin
        assertEquals(LoginState.CreatePinStep.First, state.step)
        assertTrue(state.error == "Konfirmasi PIN tidak sama")
    }

    @Test
    fun weak_pin_is_rejected() = runTest(dispatcher) {
        val store = InMemoryAuthStore()
        val model = viewModel(store)
        testScheduler.runCurrent()

        model.type("123456")
        model.type("123456")
        testScheduler.runCurrent()

        assertNull(store.credential())
        assertTrue((model.state.value as LoginState.CreatePin).error == "PIN terlalu mudah ditebak")
    }

    @Test
    fun matching_create_pin_is_saved_and_unlocks() = runTest(dispatcher) {
        val store = InMemoryAuthStore()
        val model = viewModel(store)
        testScheduler.runCurrent()
        var unlocks = 0
        backgroundScope.launch { model.unlocked.collect { unlocks++ } }
        testScheduler.runCurrent()

        model.type("246810")
        model.type("246810")
        testScheduler.runCurrent()

        assertTrue(store.credential() != null)
        assertTrue(store.isSetupComplete())
        assertEquals(1, unlocks)
    }

    @Test
    fun reset_requires_typed_confirmation() = runTest(dispatcher) {
        val store = InMemoryAuthStore(credential("246810"))
        val model = viewModel(store)
        testScheduler.runCurrent()

        model.resetApp("hapus")
        testScheduler.runCurrent()
        assertFalse(erased)
        assertTrue(store.credential() != null)

        model.resetApp("HAPUS")
        testScheduler.runCurrent()
        assertTrue(erased)
        assertTrue(model.state.value is LoginState.CreatePin)
    }

    private companion object {
        const val BASE_NOW_MS = 1_000L
    }

    private class FakeHasher : PinHasher {
        override val iterations: Int = 1_000
        var verifyCalls = 0

        override fun newSalt(): ByteArray = ByteArray(16) { (it + 1).toByte() }

        override fun hash(pin: CharArray, salt: ByteArray): ByteArray =
            pin.concatToString().encodeToByteArray()

        override fun verify(pin: CharArray, credential: PinCredential): Boolean {
            verifyCalls += 1
            return pin.concatToString().encodeToByteArray().contentEquals(credential.hash)
        }
    }
}