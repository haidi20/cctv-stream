package id.warung.monitor.feature.settings.domain

/**
 * Validation for the optional VPS base URL.
 *
 * The rule mirrors [id.warung.monitor.core.sync.SyncEndpoint]: HTTPS only,
 * because the device token and occupancy metadata travel in the body and
 * header. The message never echoes the rejected text, so a pasted password
 * cannot end up in a snackbar or a log.
 */
object SyncUrl {

    const val MAX_LENGTH: Int = 200

    sealed interface Result {
        data class Valid(val normalized: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun validate(raw: String): Result {
        val value = raw.trim().trimEnd('/')
        return when {
            value.isEmpty() -> Result.Invalid("Alamat server wajib diisi")
            value.length > MAX_LENGTH -> Result.Invalid("Alamat server terlalu panjang")
            value.any(Char::isWhitespace) -> Result.Invalid("Alamat server tidak boleh mengandung spasi")
            !value.startsWith("https://") -> Result.Invalid("Alamat server harus diawali https://")
            value.contains('?') || value.contains('#') ->
                Result.Invalid("Alamat server tidak boleh memuat query atau fragmen")
            value.substringAfter("https://").substringBefore('/').contains('@') ->
                Result.Invalid("Alamat server tidak boleh memuat kredensial")
            value.substringAfter("https://").substringBefore('/').isEmpty() ->
                Result.Invalid("Alamat server tidak lengkap")
            else -> Result.Valid(value)
        }
    }
}
