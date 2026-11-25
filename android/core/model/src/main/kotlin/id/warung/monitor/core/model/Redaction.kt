package id.warung.monitor.core.model

/**
 * Central redaction for anything shown to the user or written to a log.
 *
 * Table JSON never contains credentials, but engine errors can echo a URL or a
 * password. Every message that leaves the data layer goes through this object.
 */
object Redaction {

    private val patterns = listOf(
        Regex("(?i)rtsp://[^\\s\"']+"),
        Regex("(?i)(password|passwd|pwd|secret|token)\\s*[=:]\\s*[^\\s,;\"']+"),
        Regex("^[^:]+:[^@]+@"),
    )

    private const val MAX_MESSAGE_LENGTH = 240

    /** Strips URLs and credential-looking fragments, then bounds the length. */
    fun message(raw: String?): String {
        if (raw.isNullOrBlank()) return "Terjadi kesalahan"
        var result = raw.trim()
        for (pattern in patterns) {
            result = pattern.replace(result, "[diredaksi]")
        }
        result = result.replace(Regex("\\s+"), " ")
        if (result.length > MAX_MESSAGE_LENGTH) {
            result = result.take(MAX_MESSAGE_LENGTH - 1) + "…"
        }
        return result
    }
}