package id.warung.monitor.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * `POST_NOTIFICATIONS` is runtime-only from Android 13.
 *
 * The prompt is offered when monitoring first starts, never at login, and a
 * denial does not block monitoring: the foreground service still runs, the user
 * just does not see its notification.
 */
object NotificationPermission {

    const val SDK_TIRAMISU: Int = Build.VERSION_CODES.TIRAMISU

    /** Pure rule so the JVM can test it without an Android runtime. */
    fun shouldRequest(sdkInt: Int, granted: Boolean): Boolean =
        sdkInt >= SDK_TIRAMISU && !granted

    /**
     * Whether the notification permission is held. Before Android 13 it does
     * not exist and notifications need no runtime grant, so it reads `true`.
     */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < SDK_TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * The permission to request now, or `null` when asking would be pointless
     * (older Android or already granted).
     *
     * `InlinedApi` is suppressed because the constant is a compile-time string
     * and the SDK check above runs first; no API-33 symbol is loaded.
     */
    @SuppressLint("InlinedApi")
    fun permissionToRequest(context: Context): String? {
        if (Build.VERSION.SDK_INT < SDK_TIRAMISU) return null
        if (isGranted(context)) return null
        return Manifest.permission.POST_NOTIFICATIONS
    }
}