package id.warung.monitor.feature.auth.data

import android.content.Context
import android.provider.Settings
import android.os.SystemClock

/**
 * Identifies the current boot so persisted monotonic deadlines can be trusted.
 *
 * `elapsedRealtime` resets on reboot, so a deadline stored before a reboot
 * would otherwise float far in the future. `BOOT_COUNT` is stable for the
 * lifetime of a boot and changes exactly on reboot.
 */
object BootClock {
    fun token(context: Context): String {
        val bootCount = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.BOOT_COUNT,
            0,
        )
        // Fall back to a coarse uptime bucket only if BOOT_COUNT is unavailable.
        return if (bootCount > 0) "boot-$bootCount" else "uptime-${SystemClock.elapsedRealtime() / 60_000}"
    }
}