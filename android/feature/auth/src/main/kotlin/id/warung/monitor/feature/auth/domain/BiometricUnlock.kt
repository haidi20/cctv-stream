package id.warung.monitor.feature.auth.domain

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional biometric factor in front of the local PIN.
 *
 * Implementations must never store or reveal PIN material. A failed prompt is
 * not an error state: the login screen simply stays on the PIN keypad, so the
 * user can always fall back without losing attempts.
 */
interface BiometricUnlock {
    /** True only when the sensor exists, is enrolled, and is usable right now. */
    fun isAvailable(): Boolean

    /** Shows the system prompt; exactly one terminal callback per call. */
    fun prompt(onSuccess: () -> Unit, onFailure: () -> Unit)

    /** Dismisses an in-flight prompt, e.g. when the screen leaves composition. */
    fun cancel()
}

/**
 * Serialises prompts coming from the automatic attempt and the "ID" key.
 *
 * Kept platform-free so the rule "one prompt at a time, success unlocks once"
 * is testable on the JVM; the Android implementation only draws the prompt.
 */
class BiometricUnlocker(private val unlock: BiometricUnlock?) {

    val available: Boolean
        get() = unlock?.isAvailable() == true

    private val inFlight = AtomicBoolean(false)

    fun request(onUnlocked: () -> Unit) {
        val target = unlock ?: return
        if (!target.isAvailable()) return
        if (!inFlight.compareAndSet(false, true)) return
        target.prompt(
            onSuccess = {
                if (inFlight.compareAndSet(true, false)) onUnlocked()
            },
            onFailure = { inFlight.set(false) },
        )
    }

    fun cancel() {
        unlock?.cancel()
    }
}