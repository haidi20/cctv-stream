package id.warung.monitor.feature.auth.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.warung.monitor.feature.auth.data.AuthStore
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns PIN entry, first-run PIN creation, lockout, and unlock events.
 *
 * PIN digits live only in local char arrays. They are never copied into
 * [LoginState], a `SavedStateHandle`, or a log. PBKDF2 runs on
 * [Dispatchers.Default] because it is intentionally expensive.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val store: AuthStore,
    private val hasher: PinHasher,
    private val policy: LockoutPolicy,
    private val clock: MonotonicClock,
    private val eraser: LocalDataEraser,
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _state = MutableStateFlow<LoginState>(LoginState.Loading)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private val _unlocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unlocked: SharedFlow<Unit> = _unlocked.asSharedFlow()

    private val digits = CharArray(PinPolicy.LENGTH)
    private var digitCount = 0

    private val createDigits = CharArray(PinPolicy.LENGTH)
    private var createCount = 0
    private var firstPin: CharArray? = null

    private val changeDigits = CharArray(PinPolicy.LENGTH)
    private var changeCount = 0
    private var newPin: CharArray? = null

    private val resetDigits = CharArray(PinPolicy.LENGTH)
    private var resetCount = 0
    private var resetNewPin: CharArray? = null

    private var failures = 0
    private var lockDeadline = 0L
    private var countdown: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val credential = store.credential()
        failures = store.failures()
        lockDeadline = store.lockedUntilElapsedMs() ?: 0L
        _state.value = when {
            credential == null -> LoginState.CreatePin(LoginState.CreatePinStep.First)
            remainingLockMs() > 0 -> LoginState.LockedOut(remainingLockMs())
            else -> LoginState.EnterPin(attemptsLeft = policy.attemptsLeft(failures))
        }
        if (_state.value is LoginState.LockedOut) startCountdown()
    }

    fun appendDigit(digit: Char) {
        if (digit !in '0'..'9') return
        when (val state = _state.value) {
            is LoginState.EnterPin -> appendLoginDigit(digit, state)
            is LoginState.CreatePin -> appendCreateDigit(digit, state)
            is LoginState.ChangePin -> appendChangeDigit(digit, state)
            is LoginState.ResetPin -> appendResetDigit(digit, state)
            else -> Unit
        }
    }

    fun deleteDigit() {
        when (val state = _state.value) {
            is LoginState.EnterPin -> {
                if (digitCount == 0) return
                digits[--digitCount] = '\u0000'
                _state.value = state.copy(filled = digitCount, message = null)
            }
            is LoginState.CreatePin -> deleteCreateDigit(state)
            is LoginState.ChangePin -> deleteChangeDigit(state)
            is LoginState.ResetPin -> deleteResetDigit(state)
            else -> Unit
        }
    }

    private fun appendLoginDigit(digit: Char, state: LoginState.EnterPin) {
        if (digitCount >= PinPolicy.LENGTH) return
        digits[digitCount++] = digit
        if (digitCount == PinPolicy.LENGTH) {
            val pin = digits.copyOf(digitCount)
            clearDigits()
            verify(pin)
        } else {
            _state.value = state.copy(filled = digitCount, message = null)
        }
    }

    private fun verify(pin: CharArray) {
        _state.value = LoginState.Verifying()
        viewModelScope.launch {
            val credential = withContext(dispatcher) { store.credential() }
            if (credential == null) {
                pin.fill('\u0000')
                _state.value = LoginState.CreatePin(LoginState.CreatePinStep.First)
                return@launch
            }
            val ok = withContext(dispatcher) { hasher.verify(pin, credential) }
            pin.fill('\u0000')
            if (ok) {
                store.resetFailures()
                failures = 0
                lockDeadline = 0L
                _state.value = LoginState.EnterPin()
                _unlocked.tryEmit(Unit)
            } else {
                failOnce()
            }
        }
    }

    private suspend fun failOnce() {
        failures += 1
        val lockMs = policy.lockMillisFor(failures)
        if (lockMs != null) {
            lockDeadline = clock.nowMs() + lockMs
            store.recordFailure(failures, lockDeadline)
            startCountdown()
        } else {
            store.recordFailure(failures, null)
            _state.value = LoginState.EnterPin(
                filled = 0,
                attemptsLeft = policy.attemptsLeft(failures),
                message = "PIN salah. Sisa ${policy.attemptsLeft(failures)} percobaan.",
            )
        }
    }

    private fun startCountdown() {
        countdown?.cancel()
        val remaining = remainingLockMs()
        if (remaining <= 0) {
            lockDeadline = 0L
            viewModelScope.launch {
                store.recordFailure(failures, null)
                _state.value = LoginState.EnterPin(attemptsLeft = policy.attemptsLeft(failures))
            }
            return
        }
        _state.value = LoginState.LockedOut(remaining)
        countdown = viewModelScope.launch {
            var left = remaining
            while (left > 0) {
                delay(minOf(left, 250L))
                left = remainingLockMs()
                if (left > 0) _state.value = LoginState.LockedOut(left)
            }
            lockDeadline = 0L
            store.recordFailure(failures, null)
            _state.value = LoginState.EnterPin(attemptsLeft = policy.attemptsLeft(failures))
        }
    }

    private fun remainingLockMs(): Long {
        val deadline = lockDeadline
        if (deadline == 0L) return 0L
        return (deadline - clock.nowMs()).coerceAtLeast(0L)
    }

    private fun appendCreateDigit(digit: Char, state: LoginState.CreatePin) {
        if (createCount >= PinPolicy.LENGTH) return
        createDigits[createCount++] = digit
        when (state.step) {
            LoginState.CreatePinStep.First -> {
                if (createCount < PinPolicy.LENGTH) {
                    _state.value = state.copy(firstFilled = createCount, error = null)
                } else {
                    firstPin?.fill('\u0000')
                    firstPin = createDigits.copyOf(PinPolicy.LENGTH)
                    createDigits.fill('\u0000')
                    createCount = 0
                    _state.value = LoginState.CreatePin(LoginState.CreatePinStep.Confirm)
                }
            }
            LoginState.CreatePinStep.Confirm -> {
                if (createCount < PinPolicy.LENGTH) {
                    _state.value = state.copy(confirmFilled = createCount, error = null)
                } else {
                    val confirmation = createDigits.copyOf(PinPolicy.LENGTH)
                    createDigits.fill('\u0000')
                    createCount = 0
                    finishPinCreation(confirmation)
                }
            }
        }
    }

    private fun deleteCreateDigit(state: LoginState.CreatePin) {
        when (state.step) {
            LoginState.CreatePinStep.First -> {
                if (createCount == 0) return
                createDigits[--createCount] = '\u0000'
                _state.value = state.copy(firstFilled = createCount, error = null)
            }
            LoginState.CreatePinStep.Confirm -> {
                if (createCount == 0) {
                    // Going back to step one drops the saved first PIN.
                    firstPin?.fill('\u0000')
                    firstPin = null
                    _state.value = LoginState.CreatePin(LoginState.CreatePinStep.First)
                    return
                }
                createDigits[--createCount] = '\u0000'
                _state.value = state.copy(confirmFilled = createCount, error = null)
            }
        }
    }

    private fun finishPinCreation(confirmation: CharArray) {
        val first = firstPin
        if (first == null) {
            confirmation.fill('\u0000')
            _state.value = LoginState.CreatePin(LoginState.CreatePinStep.First)
            return
        }
        if (!first.contentEquals(confirmation)) {
            confirmation.fill('\u0000')
            first.fill('\u0000')
            firstPin = null
            _state.value = LoginState.CreatePin(
                LoginState.CreatePinStep.First,
                error = "Konfirmasi PIN tidak sama",
            )
            return
        }
        confirmation.fill('\u0000')
        if (PinPolicy.isWeak(first)) {
            first.fill('\u0000')
            firstPin = null
            _state.value = LoginState.CreatePin(
                LoginState.CreatePinStep.First,
                error = "PIN terlalu mudah ditebak",
            )
            return
        }
        val pinToSave = first.copyOf()
        _state.value = LoginState.Verifying()
        viewModelScope.launch {
            try {
                val salt = hasher.newSalt()
                val hash = withContext(dispatcher) { hasher.hash(pinToSave, salt) }
                store.saveCredential(PinCredential(salt, hash, hasher.iterations))
                store.markSetupComplete()
                store.resetFailures()
                failures = 0
                _state.value = LoginState.EnterPin()
                _unlocked.tryEmit(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.value = LoginState.CreatePin(
                    LoginState.CreatePinStep.Confirm,
                    error = "Gagal menyimpan PIN",
                )
            } finally {
                pinToSave.fill('\u0000')
                first.fill('\u0000')
                firstPin = null
            }
        }
    }

    /** Opens the three-step PIN change. Ignored while locked out or unset. */
    fun startPinChange() {
        if (_state.value !is LoginState.EnterPin) return
        clearDigits()
        newPin?.fill('\u0000')
        newPin = null
        changeDigits.fill('\u0000')
        changeCount = 0
        _state.value = LoginState.ChangePin(LoginState.ChangePinStep.Current)
    }

    /** Leaves PIN change without touching the stored credential. */
    fun cancelPinChange() {
        if (_state.value !is LoginState.ChangePin) return
        clearChangeDigits()
        _state.value = LoginState.EnterPin(attemptsLeft = policy.attemptsLeft(failures))
    }

    private fun appendChangeDigit(digit: Char, state: LoginState.ChangePin) {
        if (changeCount >= PinPolicy.LENGTH) return
        changeDigits[changeCount++] = digit
        if (changeCount < PinPolicy.LENGTH) {
            _state.value = state.copy(filled = changeCount, error = null)
            return
        }
        val entered = changeDigits.copyOf(PinPolicy.LENGTH)
        changeDigits.fill('\u0000')
        changeCount = 0
        when (state.step) {
            LoginState.ChangePinStep.Current -> verifyCurrentPin(entered)
            LoginState.ChangePinStep.New -> acceptNewPin(entered)
            LoginState.ChangePinStep.Confirm -> finishPinChange(entered)
        }
    }

    private fun deleteChangeDigit(state: LoginState.ChangePin) {
        when (state.step) {
            LoginState.ChangePinStep.Current -> {
                if (changeCount == 0) return
                changeDigits[--changeCount] = '\u0000'
                _state.value = state.copy(filled = changeCount, error = null)
            }
            LoginState.ChangePinStep.New -> {
                if (changeCount == 0) {
                    // Going back to the old-PIN step drops the pending new PIN.
                    newPin?.fill('\u0000')
                    newPin = null
                    _state.value = LoginState.ChangePin(LoginState.ChangePinStep.Current)
                    return
                }
                changeDigits[--changeCount] = '\u0000'
                _state.value = state.copy(filled = changeCount, error = null)
            }
            LoginState.ChangePinStep.Confirm -> {
                if (changeCount == 0) {
                    newPin?.fill('\u0000')
                    newPin = null
                    _state.value = LoginState.ChangePin(LoginState.ChangePinStep.New)
                    return
                }
                changeDigits[--changeCount] = '\u0000'
                _state.value = state.copy(filled = changeCount, error = null)
            }
        }
    }

    private fun verifyCurrentPin(pin: CharArray) {
        _state.value = LoginState.Verifying()
        viewModelScope.launch {
            val credential = withContext(dispatcher) { store.credential() }
            if (credential == null) {
                pin.fill('\u0000')
                _state.value = LoginState.CreatePin(LoginState.CreatePinStep.First)
                return@launch
            }
            val ok = withContext(dispatcher) { hasher.verify(pin, credential) }
            pin.fill('\u0000')
            if (ok) {
                store.resetFailures()
                failures = 0
                lockDeadline = 0L
                _state.value = LoginState.ChangePin(LoginState.ChangePinStep.New)
            } else {
                failOnce()
                // A failed old PIN must not leave the change flow running.
                if (_state.value is LoginState.EnterPin) {
                    _state.value = LoginState.ChangePin(
                        LoginState.ChangePinStep.Current,
                        error = "PIN lama salah. Sisa ${policy.attemptsLeft(failures)} percobaan.",
                    )
                }
            }
        }
    }

    private fun acceptNewPin(pin: CharArray) {
        if (PinPolicy.isWeak(pin)) {
            pin.fill('\u0000')
            _state.value = LoginState.ChangePin(
                LoginState.ChangePinStep.New,
                error = "PIN terlalu mudah ditebak",
            )
            return
        }
        newPin?.fill('\u0000')
        newPin = pin
        _state.value = LoginState.ChangePin(LoginState.ChangePinStep.Confirm)
    }

    private fun finishPinChange(confirmation: CharArray) {
        val pending = newPin
        if (pending == null) {
            confirmation.fill('\u0000')
            _state.value = LoginState.ChangePin(LoginState.ChangePinStep.New)
            return
        }
        if (!pending.contentEquals(confirmation)) {
            confirmation.fill('\u0000')
            pending.fill('\u0000')
            newPin = null
            _state.value = LoginState.ChangePin(
                LoginState.ChangePinStep.New,
                error = "Konfirmasi PIN tidak sama",
            )
            return
        }
        confirmation.fill('\u0000')
        val pinToSave = pending.copyOf()
        _state.value = LoginState.Verifying()
        viewModelScope.launch {
            try {
                val salt = hasher.newSalt()
                val hash = withContext(dispatcher) { hasher.hash(pinToSave, salt) }
                store.saveCredential(PinCredential(salt, hash, hasher.iterations))
                store.resetFailures()
                failures = 0
                lockDeadline = 0L
                _state.value = LoginState.EnterPin()
                _unlocked.tryEmit(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.value = LoginState.ChangePin(
                    LoginState.ChangePinStep.Confirm,
                    error = "Gagal menyimpan PIN",
                )
            } finally {
                pinToSave.fill('\u0000')
                pending.fill('\u0000')
                newPin = null
            }
        }
    }

    private fun clearChangeDigits() {
        changeDigits.fill('\u0000')
        changeCount = 0
        newPin?.fill('\u0000')
        newPin = null
    }

    private fun appendResetDigit(digit: Char, state: LoginState.ResetPin) {
        if (resetCount >= PinPolicy.LENGTH) return
        resetDigits[resetCount++] = digit
        if (resetCount < PinPolicy.LENGTH) {
            _state.value = state.copy(filled = resetCount, error = null)
            return
        }
        val entered = resetDigits.copyOf(PinPolicy.LENGTH)
        resetDigits.fill('\u0000')
        resetCount = 0
        when (state.step) {
            LoginState.ResetPinStep.New -> acceptResetPin(entered)
            LoginState.ResetPinStep.Confirm -> finishResetPin(entered)
        }
    }

    private fun deleteResetDigit(state: LoginState.ResetPin) {
        when (state.step) {
            LoginState.ResetPinStep.New -> {
                if (resetCount == 0) {
                    cancelResetPin()
                    return
                }
                resetDigits[--resetCount] = '\u0000'
                _state.value = state.copy(filled = resetCount, error = null)
            }
            LoginState.ResetPinStep.Confirm -> {
                if (resetCount == 0) {
                    resetNewPin?.fill('\u0000')
                    resetNewPin = null
                    _state.value = LoginState.ResetPin(LoginState.ResetPinStep.New)
                    return
                }
                resetDigits[--resetCount] = '\u0000'
                _state.value = state.copy(filled = resetCount, error = null)
            }
        }
    }

    private fun acceptResetPin(pin: CharArray) {
        if (PinPolicy.isWeak(pin)) {
            pin.fill('\u0000')
            _state.value = LoginState.ResetPin(
                LoginState.ResetPinStep.New,
                error = "PIN terlalu mudah ditebak",
            )
            return
        }
        resetNewPin?.fill('\u0000')
        resetNewPin = pin
        _state.value = LoginState.ResetPin(LoginState.ResetPinStep.Confirm)
    }

    private fun finishResetPin(confirmation: CharArray) {
        val pending = resetNewPin
        if (pending == null) {
            confirmation.fill('\u0000')
            _state.value = LoginState.ResetPin(LoginState.ResetPinStep.New)
            return
        }
        if (!pending.contentEquals(confirmation)) {
            confirmation.fill('\u0000')
            pending.fill('\u0000')
            resetNewPin = null
            _state.value = LoginState.ResetPin(
                LoginState.ResetPinStep.New,
                error = "Konfirmasi PIN tidak sama",
            )
            return
        }
        confirmation.fill('\u0000')
        val pinToSave = pending.copyOf()
        _state.value = LoginState.Verifying()
        viewModelScope.launch {
            try {
                val salt = hasher.newSalt()
                val hash = withContext(dispatcher) { hasher.hash(pinToSave, salt) }
                store.saveCredential(PinCredential(salt, hash, hasher.iterations))
                store.markSetupComplete()
                store.resetFailures()
                failures = 0
                lockDeadline = 0L
                countdown?.cancel()
                _state.value = LoginState.EnterPin()
                _unlocked.tryEmit(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.value = LoginState.ResetPin(
                    LoginState.ResetPinStep.Confirm,
                    error = "Gagal menyimpan PIN",
                )
            } finally {
                pinToSave.fill('\u0000')
                pending.fill('\u0000')
                resetNewPin = null
            }
        }
    }

    /**
     * Opens the two-form PIN reset from the keypad screen.
     *
     * Only a configured credential can be reset; a first-run device keeps the
     * create-PIN flow so the user cannot end up with no credential at all.
     */
    fun startResetPin() {
        if (_state.value !is LoginState.EnterPin) return
        resetNewPin?.fill('\u0000')
        resetNewPin = null
        resetDigits.fill('\u0000')
        resetCount = 0
        clearDigits()
        _state.value = LoginState.ResetPin(LoginState.ResetPinStep.New)
    }

    /** Leaves the reset flow without touching the stored credential. */
    fun cancelResetPin() {
        if (_state.value !is LoginState.ResetPin) return
        resetDigits.fill('\u0000')
        resetCount = 0
        resetNewPin?.fill('\u0000')
        resetNewPin = null
        _state.value = LoginState.EnterPin(attemptsLeft = policy.attemptsLeft(failures))
    }

    /** Clears the stored PIN and every local table map; requires `HAPUS`. */
    fun resetApp(typedConfirmation: String) {
        if (typedConfirmation != RESET_CONFIRMATION) return
        viewModelScope.launch {
            eraser.eraseAll()
            failures = 0
            lockDeadline = 0L
            countdown?.cancel()
            clearDigits()
            firstPin?.fill('\u0000')
            firstPin = null
            createDigits.fill('\u0000')
            createCount = 0
            clearChangeDigits()
            _state.value = LoginState.CreatePin(LoginState.CreatePinStep.First)
        }
    }

    fun startPinCreation() {
        if (_state.value is LoginState.CreatePin) return
        firstPin?.fill('\u0000')
        firstPin = null
        createDigits.fill('\u0000')
        createCount = 0
        clearChangeDigits()
        _state.value = LoginState.CreatePin(LoginState.CreatePinStep.First)
    }

    private fun clearDigits() {
        digits.fill('\u0000')
        digitCount = 0
    }

    override fun onCleared() {
        clearDigits()
        createDigits.fill('\u0000')
        firstPin?.fill('\u0000')
        clearChangeDigits()
        resetDigits.fill('\u0000')
        resetNewPin?.fill('\u0000')
        countdown?.cancel()
        super.onCleared()
    }

    companion object {
        const val RESET_CONFIRMATION: String = "HAPUS"
    }
}
