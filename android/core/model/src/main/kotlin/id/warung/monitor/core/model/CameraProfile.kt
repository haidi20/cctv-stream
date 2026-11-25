package id.warung.monitor.core.model

/**
 * Stable camera identifier shared by Room, the UI, and the Rust engine.
 *
 * The allowed alphabet is the same one `core-ffi` enforces. Rejecting `:`, `/`,
 * and `@` here means an identifier can never smuggle a full RTSP URL with a
 * password into a log or an error message.
 */
@JvmInline
value class CameraId(val value: String) {
    init {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "Camera id must not be blank" }
        require(trimmed.length <= MAX_LENGTH) { "Camera id is too long" }
        require(trimmed.all { it.isAsciiIdCharacter() }) {
            "Camera id may only contain A-Z a-z 0-9 . _ -"
        }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH: Int = 64

        private fun Char.isAsciiIdCharacter(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '.' || this == '_' || this == '-'
    }
}

/** Human readable camera name; editing it never changes [CameraId]. */
@JvmInline
value class CameraLabel(val value: String) {
    init {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "Camera label must not be blank" }
        require(trimmed.length <= MAX_LENGTH) { "Camera label is too long" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH: Int = 40
    }
}

/** Where a camera's frames come from. Passwords are never part of a profile. */
sealed interface CameraSource {

    /** Built-in demo frame used until the RTSP preview pipeline lands. */
    data object Demo : CameraSource

    /**
     * RTSP endpoint without credentials.
     *
     * `username` is optional and carries no password. A real pipeline must read
     * the secret from Android Keystore, keyed by [CameraId].
     */
    data class Rtsp(
        val host: String,
        val port: Int,
        val path: String,
        val username: String? = null,
    ) : CameraSource {
        init {
            require(host.isValidHost()) { "Camera host is not valid" }
            require(port in 1..65535) { "Camera port must be 1..65535" }
            require(path.isValidPath()) { "Camera path is not valid" }
            require(username == null || username.isValidUsername()) { "Camera username is not valid" }
        }

        /** `host:port` only; safe for a log line or a subtitle. */
        val displayEndpoint: String get() = "$host:$port"

        private fun String.isValidHost(): Boolean =
            isNotBlank() &&
                length <= 255 &&
                none { it == '/' || it == '@' || it == ':' || it.isWhitespace() } &&
                all { it.isLetterOrDigit() || it == '.' || it == '-' }

        private fun String.isValidPath(): Boolean =
            startsWith('/') &&
                length in 2..512 &&
                none { it == '@' || it.isWhitespace() } &&
                !contains("://")

        private fun String.isValidUsername(): Boolean =
            isNotBlank() &&
                length <= 128 &&
                none { it == ':' || it == '@' || it == '/' || it.isWhitespace() }
    }
}

/** A camera the user can open, one at a time. */
data class CameraProfile(
    val id: CameraId,
    val label: CameraLabel,
    val source: CameraSource,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long? = null,
) {
    /** Safe one-line description for lists and logs. */
    val sourceDescription: String
        get() = when (source) {
            CameraSource.Demo -> "Demo"
            is CameraSource.Rtsp -> "RTSP - ${source.displayEndpoint}"
        }
}