package id.warung.monitor.feature.auth.domain

/** Rejects the handful of 6-digit patterns a stranger would try first. */
object ResetPolicy {
    /** Typed by the user before a full local reset. */
    const val CONFIRMATION: String = "HAPUS"
}

object PinPolicy {

    const val LENGTH: Int = 6

    private val blocked = setOf("123456", "654321", "000000", "111111", "121212", "112233")

    fun isWellFormed(pin: CharArray): Boolean =
        pin.size == LENGTH && pin.all { it in '0'..'9' }

    fun isWeak(pin: CharArray): Boolean {
        if (!isWellFormed(pin)) return true
        val text = String(pin)
        if (text in blocked) return true
        if (text.all { it == text.first() }) return true
        return isSequence(pin, step = 1) || isSequence(pin, step = -1)
    }

    private fun isSequence(pin: CharArray, step: Int): Boolean =
        pin.toList().zipWithNext().all { (a, b) -> b - a == step }
}