package id.warung.monitor.session

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import id.warung.monitor.feature.auth.domain.BiometricUnlock
import java.util.concurrent.Executor

/**
 * Biometric unlock backed by the platform prompt (API 28+), not
 * `androidx.biometric`.
 *
 * Reasons for the platform API: `minSdk` is 29, so the compat code path only
 * exists for older devices; `androidx.biometric` 1.1.0 requires a
 * `FragmentActivity` plus `appcompat`, and the 1.4.0-rc01 `Context`
 * constructor is `@RestrictTo(LIBRARY)`. The platform prompt keeps the APK
 * free of a UI dependency for one convenience action. Documented in
 * `docs/android-app-plan.md` 3.1.
 *
 * The prompt never receives, stores, or returns PIN material: success only
 * means the platform verified the user, and the caller then unlocks the local
 * session. Cancellation and errors fall back to the PIN keypad.
 */
class AndroidBiometricUnlock(private val activity: Activity) : BiometricUnlock {

    private val executor = Executor { command -> activity.runOnUiThread(command) }
    private var signal: CancellationSignal? = null

    override fun isAvailable(): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val manager = activity.getSystemService(BiometricManager::class.java) ?: return false
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        val hasBiometrics = status == BiometricManager.BIOMETRIC_SUCCESS
        if (hasBiometrics) return true
        val keyguard = activity.getSystemService(KeyguardManager::class.java) ?: return false
        return keyguard.isDeviceSecure
    }

    override fun prompt(onSuccess: () -> Unit, onFailure: () -> Unit) {
        if (!isAvailable()) {
            onFailure()
            return
        }
        val cancellation = CancellationSignal()
        signal = cancellation
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult?,
            ) {
                signal = null
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                signal = null
                onFailure()
            }
        }
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("Buka Warung Monitor")
            .setSubtitle("Gunakan biometrik atau kunci perangkat")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        try {
            builder.build().authenticate(cancellation, executor, callback)
        } catch (_: Throwable) {
            // The activity may not be resumed yet; the keypad is always usable.
            signal = null
            onFailure()
        }
    }

    override fun cancel() {
        signal?.cancel()
        signal = null
    }
}