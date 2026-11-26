package id.warung.monitor.sync

import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `session_id` for heartbeats: one value per foreground-service start.
 *
 * In memory only. A process death always produces a new value, which is exactly
 * the grouping the dashboard wants; persisting it would let a crash look like
 * the same session.
 */
@Singleton
class HeartbeatSession @Inject constructor() {

    private val random = SecureRandom()

    @Volatile
    private var current: String = newId()

    fun current(): String = current

    /** Called when monitoring (re)starts; never on every heartbeat. */
    @Synchronized
    fun rotate(): String {
        current = newId()
        return current
    }

    private fun newId(): String {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }
}
