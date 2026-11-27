package id.warung.monitor.feature.auth.domain

/**
 * Login screen state.
 *
 * Deliberately contains no PIN material: no partial PIN, no full PIN. Only the
 * number of filled dots is exposed. `toString()` of every variant is safe to
 * log.
 */
sealed interface LoginState {
    data object Loading : LoginState

    data class EnterPin(
        val filled: Int = 0,
        val attemptsLeft: Int? = null,
        val message: String? = null,
    ) : LoginState {
        init {
            require(filled in 0..PinPolicy.LENGTH) { "filled out of range" }
        }
    }

    data class Verifying(val filled: Int = PinPolicy.LENGTH) : LoginState

    data class LockedOut(val remainingMs: Long) : LoginState

    data class CreatePin(
        val step: CreatePinStep,
        val firstFilled: Int = 0,
        val confirmFilled: Int = 0,
        val error: String? = null,
    ) : LoginState

    /**
     * Three-step PIN change: prove the old PIN, type the new one twice.
     * [filled] counts dots for the step in progress only.
     */
    data class ChangePin(
        val step: ChangePinStep,
        val filled: Int = 0,
        val error: String? = null,
    ) : LoginState {
        init {
            require(filled in 0..PinPolicy.LENGTH) { "filled out of range" }
        }
    }

    enum class CreatePinStep { First, Confirm }

    enum class ChangePinStep { Current, New, Confirm }
}

/** Result of submitting a six-digit PIN. */
sealed interface LoginOutcome {
    data object Success : LoginOutcome
    data class Failure(val attemptsLeft: Int, val message: String?) : LoginOutcome
    data class LockedOut(val remainingMs: Long) : LoginOutcome
    data object NotConfigured : LoginOutcome
}

/** Persisted credential record. The PIN hash is never exposed as a String. */
data class PinCredential(
    val salt: ByteArray,
    val hash: ByteArray,
    val iterations: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PinCredential) return false
        return salt.contentEquals(other.salt) &&
            hash.contentEquals(other.hash) &&
            iterations == other.iterations
    }

    override fun hashCode(): Int {
        var result = salt.contentHashCode()
        result = 31 * result + hash.contentHashCode()
        result = 31 * result + iterations
        return result
    }

    override fun toString(): String = "PinCredential(iterations=$iterations)"
}