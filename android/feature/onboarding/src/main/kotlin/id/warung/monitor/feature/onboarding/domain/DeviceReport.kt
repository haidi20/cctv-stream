package id.warung.monitor.feature.onboarding.domain

import id.warung.monitor.core.model.Redaction

/** One status in the four-row diagnostic list. */
enum class CheckStatus(val label: String) {
    Pass("Lulus"),
    Warning("Peringatan"),
    Unsupported("Tidak didukung"),
    Skipped("Dilewati"),
}

/** Colour policy, kept pure so it can be unit-tested without Compose. */
enum class CheckTone {
    Positive,
    Caution,
    Blocked,
    Muted,
}

fun toneOf(status: CheckStatus): CheckTone = when (status) {
    CheckStatus.Pass -> CheckTone.Positive
    CheckStatus.Warning -> CheckTone.Caution
    CheckStatus.Unsupported -> CheckTone.Blocked
    CheckStatus.Skipped -> CheckTone.Muted
}

enum class DeviceCheckId(val title: String) {
    Platform("Android dan prosesor"),
    Memory("Memori"),
    Engine("Mesin AI lokal"),
    Storage("Penyimpanan dan izin"),
}

data class DeviceCheckRow(
    val id: DeviceCheckId,
    val status: CheckStatus,
    /** Everyday Indonesian; no `arm64-v8a` or file paths. */
    val summary: String,
    /** Expandable technical line. Still redacted before leaving the device. */
    val technical: String? = null,
    val errorCode: String? = null,
)

enum class DeviceProfile(val label: String) {
    Full("Penuh"),
    Balanced("Seimbang"),
    Lite("Hemat"),
    Blocked("Tidak didukung"),
}

/** Immutable diagnostic result rendered by the onboarding screen. */
data class DeviceReport(
    val profile: DeviceProfile,
    val rows: List<DeviceCheckRow>,
    val engineReady: Boolean,
    val blockedCode: String?,
    val appVersionCode: Long,
    val appVersionName: String,
) {
    val isBlocked: Boolean get() = profile == DeviceProfile.Blocked

    val hasWarning: Boolean
        get() = rows.any { it.status == CheckStatus.Warning || it.status == CheckStatus.Skipped }

    val blockedReason: String?
        get() = rows.firstOrNull { it.status == CheckStatus.Unsupported }?.summary

    val warningReasons: List<String>
        get() = rows.filter { it.status == CheckStatus.Warning || it.status == CheckStatus.Skipped }
            .map { it.summary }

    /**
     * Clipboard text. Credentials, URLs, and SSIDs never enter the report, and
     * every line still passes through [Redaction] as defence in depth.
     */
    fun toClipboardText(): String {
        val builder = StringBuilder()
        builder.appendLine("Laporan pemeriksaan perangkat - Warung Monitor")
        builder.appendLine("Versi aplikasi: ${appVersionName.ifBlank { "tidak diketahui" }} ($appVersionCode)")
        builder.appendLine("Profil: ${profile.label}")
        builder.appendLine("Mesin AI siap: ${if (engineReady) "ya" else "belum"}")
        builder.appendLine()
        for (row in rows) {
            builder.append(row.id.title).append(": ").append(row.status.label)
            builder.append(" - ").append(Redaction.message(row.summary))
            val code = row.errorCode
            if (code != null) builder.append(" [").append(code).append("]")
            builder.appendLine()
        }
        val technical = rows.mapNotNull { row ->
            row.technical?.let { detail -> "${row.id.title}: $detail" }
        }
        if (technical.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("Detail teknis:")
            for (line in technical) {
                builder.appendLine(Redaction.message(line))
            }
        }
        return builder.toString().trimEnd()
    }
}