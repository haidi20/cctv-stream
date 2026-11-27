package id.warung.monitor.core.model

/** Longest video file name accepted for a dev-mode camera. */
private const val MAX_FILE_NAME_LENGTH: Int = 120

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

/**
 * Stable device identity reported by discovery, never an endpoint or a secret.
 *
 * ONVIF calls this an endpoint reference; `urn:uuid:` is accepted so a raw
 * discovery payload can be stored directly. Both forms are normalised to
 * lowercase because UUIDs are case-insensitive. An IP, URL, credential, or a
 * free-form string is rejected: if a camera has no UUID, the caller keeps its
 * endpoint as identity instead.
 */
@JvmInline
value class CameraDeviceId(val value: String) {
    init {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "Device id must not be blank" }
        require(trimmed.length <= MAX_LENGTH) { "Device id is too long" }
        require(UUID_PATTERN.matches(trimmed)) { "Device id must be a UUID" }
    }

    override fun toString(): String = value

    companion object {
        private const val MAX_LENGTH: Int = 64
        private val UUID_PATTERN = Regex(
            "(?i)^(?:urn:uuid:)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )

        /** Returns null instead of throwing for a missing or unusable address. */
        fun from(address: String?): CameraDeviceId? {
            val trimmed = address?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                val candidate = trimmed.removePrefix("urn:uuid:")
                if (candidate == trimmed) CameraDeviceId(trimmed)
                else CameraDeviceId(candidate)
            }.getOrNull()?.let { CameraDeviceId(it.value.lowercase()) }
        }
    }
}

/** Where a camera's frames come from. Passwords are never part of a profile. */
sealed interface CameraSource {

    /** Built-in demo frame used until the RTSP preview pipeline lands. */
    data object Demo : CameraSource

    /**
     * A local video file used by dev mode when no camera is reachable.
     *
     * [fileName] is resolved under the app's private `files/videos` directory,
     * so nothing from `testdata/` ever ships in the APK and no absolute path
     * crosses the FFI boundary.
     */
    data class Video(val fileName: String) : CameraSource {
        init {
            require(fileName.isSafeFileName()) { "Video file name is not valid" }
        }

        /** Safe one-line description for lists and logs. */
        val displayName: String get() = fileName

        private fun String.isSafeFileName(): Boolean =
            isNotBlank() &&
                length <= MAX_FILE_NAME_LENGTH &&
                !contains('/') &&
                !contains('\\') &&
                none { it.isWhitespace() } &&
                endsWith(".mp4", ignoreCase = true)
    }

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
    /**
     * ONVIF device UUID when discovery supplied one.
     *
     * Identity and table-map lookups prefer this over the endpoint, so new IP
     * leases do not orphan a user's polygon calibration. Null means the camera
     * was added manually or probed by port only.
     */
    val deviceId: CameraDeviceId? = null,
    /**
     * Whether this camera takes part in monitoring.
     *
     * A paused camera keeps its profile and table map but must not open a
     * stream, so it cannot consume frames or CPU behind the user's back.
     */
    val monitoringEnabled: Boolean = true,
) {
    /** Safe one-line description for lists and logs. */
    val sourceDescription: String
        get() = when (source) {
            CameraSource.Demo -> "Demo"
            is CameraSource.Rtsp -> "RTSP - ${source.displayEndpoint}"
            is CameraSource.Video -> "Video - ${source.displayName}"
        }
}
