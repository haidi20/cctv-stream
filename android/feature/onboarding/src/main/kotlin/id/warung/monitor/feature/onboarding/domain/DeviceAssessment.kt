package id.warung.monitor.feature.onboarding.domain

/**
 * Maps raw capability facts to the four-row report and one device profile.
 *
 * The order mirrors `docs/device-compatibility.md`: platform and ABI first,
 * then memory, native engine, then storage and permissions. Native/model work
 * is never requested after a fatal platform result.
 */
object DeviceAssessment {
    const val MIN_API: Int = 29
    const val RECOMMENDED_API: Int = 31
    const val MIN_RAM_MB: Int = 3_072
    const val FULL_RAM_MB: Int = 4_096
    const val MIN_AVAILABLE_RAM_MB: Int = 600
    const val MIN_STORAGE_MB: Int = 1_024
    const val RECOMMENDED_STORAGE_MB: Int = 2_048

    fun assess(capability: DeviceCapability, warmUp: WarmUpResult): DeviceReport {
        val platform = assessPlatform(capability)
        val memory = assessMemory(capability)
        val earlierFatal = platform.status == CheckStatus.Unsupported ||
            memory.status == CheckStatus.Unsupported
        val engine = assessEngine(capability, warmUp, earlierFatal)
        val storage = assessStorage(capability)
        val rows = listOf(platform, memory, engine, storage)
        val blocked = rows.firstOrNull { it.status == CheckStatus.Unsupported }
        val engineReady = engine.status == CheckStatus.Pass

        val profile = when {
            blocked != null -> DeviceProfile.Blocked
            // `full` is only claimed on the recommended Android level; an older
            // but otherwise capable device runs the balanced cadence instead.
            engineReady &&
                capability.ramTotalMb >= FULL_RAM_MB &&
                capability.ramAvailableMb >= MIN_AVAILABLE_RAM_MB &&
                capability.apiLevel >= RECOMMENDED_API -> DeviceProfile.Full
            engineReady &&
                capability.ramTotalMb >= MIN_RAM_MB &&
                capability.ramAvailableMb >= MIN_AVAILABLE_RAM_MB -> DeviceProfile.Balanced
            // Without a verified model the engine cannot be trusted for either
            // cadence, so the app continues in the low-power profile only.
            else -> DeviceProfile.Lite
        }

        return DeviceReport(
            profile = profile,
            rows = rows,
            engineReady = engineReady,
            blockedCode = blocked?.errorCode,
            appVersionCode = capability.appVersionCode,
            appVersionName = capability.appVersionName,
        )
    }

    fun errorMessage(code: String): String = when (code) {
        DeviceErrorCode.MODEL_MISSING -> "Model deteksi tidak ditemukan"
        DeviceErrorCode.MODEL_CHECKSUM -> "Berkas model rusak"
        DeviceErrorCode.ONNX_INIT -> "Runtime deteksi gagal dimuat"
        DeviceErrorCode.WARMUP_FAILED -> "Uji deteksi gagal di perangkat ini"
        DeviceErrorCode.LIBRARY_LOAD -> "Pustaka pemrosesan gagal dimuat"
        DeviceErrorCode.RAM_LOW -> "Memori perangkat di bawah batas minimum"
        DeviceErrorCode.STORAGE_LOW -> "Ruang penyimpanan di bawah batas minimum"
        else -> "Mesin AI tidak dapat digunakan"
    }

    private fun assessPlatform(capability: DeviceCapability): DeviceCheckRow {
        val technical = "API ${capability.apiLevel}; ABI ${capability.abis.joinToString()}"
        return when {
            capability.apiLevel < MIN_API -> row(
                id = DeviceCheckId.Platform,
                status = CheckStatus.Unsupported,
                summary = "Android ${capability.apiLevel} terlalu lama (minimal Android 10)",
                technical = technical,
                code = DeviceErrorCode.ANDROID_TOO_OLD,
            )
            !capability.isArm64 -> row(
                id = DeviceCheckId.Platform,
                status = CheckStatus.Unsupported,
                summary = "Prosesor perangkat belum didukung; diperlukan perangkat 64-bit ARM",
                technical = technical,
                code = DeviceErrorCode.ABI_UNSUPPORTED,
            )
            capability.apiLevel < RECOMMENDED_API -> row(
                id = DeviceCheckId.Platform,
                status = CheckStatus.Warning,
                summary = "Android ${capability.apiLevel} didukung; Android 12 atau lebih baru disarankan",
                technical = technical,
            )
            else -> row(
                id = DeviceCheckId.Platform,
                status = CheckStatus.Pass,
                summary = "Android ${capability.apiLevel} dengan prosesor ARM 64-bit",
                technical = technical,
            )
        }
    }

    private fun assessMemory(capability: DeviceCapability): DeviceCheckRow {
        val technical = "RAM total ${capability.ramTotalMb} MB; tersedia ${capability.ramAvailableMb} MB"
        return when {
            capability.ramTotalMb < MIN_RAM_MB -> row(
                id = DeviceCheckId.Memory,
                status = CheckStatus.Unsupported,
                summary = "RAM total ${capability.ramTotalMb} MB; minimal 3 GB",
                technical = technical,
                code = DeviceErrorCode.RAM_LOW,
            )
            capability.ramAvailableMb < MIN_AVAILABLE_RAM_MB -> row(
                id = DeviceCheckId.Memory,
                status = CheckStatus.Warning,
                summary = "RAM tersedia ${capability.ramAvailableMb} MB; tutup aplikasi lain agar pemantauan lancar",
                technical = technical,
            )
            capability.ramTotalMb < FULL_RAM_MB -> row(
                id = DeviceCheckId.Memory,
                status = CheckStatus.Warning,
                summary = "RAM total ${capability.ramTotalMb} MB; mode hemat dipakai dengan deteksi lebih lambat",
                technical = technical,
            )
            else -> row(
                id = DeviceCheckId.Memory,
                status = CheckStatus.Pass,
                summary = "RAM ${capability.ramTotalMb} MB",
                technical = technical,
            )
        }
    }

    private fun assessEngine(
        capability: DeviceCapability,
        warmUp: WarmUpResult,
        earlierFatal: Boolean,
    ): DeviceCheckRow {
        val platformBlocked = earlierFatal ||
            capability.apiLevel < MIN_API ||
            !capability.isArm64
        val technical = buildString {
            append("Pustaka native: ")
            append(if (capability.nativeLibraryLoadable) "dimuat" else "gagal dimuat")
            append("; model: ")
            append(if (capability.modelBundled) "terpasang" else "belum dipasang")
            val warmUpDetail = when (warmUp) {
                is WarmUpResult.Passed -> "; warm-up ${warmUp.elapsedMs} ms"
                is WarmUpResult.Failed -> "; warm-up gagal (${warmUp.code})"
                WarmUpResult.Skipped -> "; warm-up dilewati"
            }
            append(warmUpDetail)
        }
        return when {
            platformBlocked -> row(
                id = DeviceCheckId.Engine,
                status = CheckStatus.Skipped,
                summary = "Tidak dijalankan karena perangkat tidak memenuhi syarat dasar",
                technical = technical,
            )
            !capability.nativeLibraryLoadable -> row(
                id = DeviceCheckId.Engine,
                status = CheckStatus.Unsupported,
                summary = errorMessage(DeviceErrorCode.LIBRARY_LOAD),
                technical = technical,
                code = DeviceErrorCode.LIBRARY_LOAD,
            )
            !capability.modelBundled -> row(
                id = DeviceCheckId.Engine,
                status = CheckStatus.Skipped,
                summary = "Model deteksi belum dipasang pada build ini; mode demo tetap tersedia",
                technical = technical,
            )
            warmUp is WarmUpResult.Passed -> row(
                id = DeviceCheckId.Engine,
                status = CheckStatus.Pass,
                summary = "Mesin AI siap (warm-up ${warmUp.elapsedMs} ms)",
                technical = technical,
            )
            warmUp is WarmUpResult.Failed -> row(
                id = DeviceCheckId.Engine,
                status = CheckStatus.Unsupported,
                summary = errorMessage(warmUp.code),
                technical = technical,
                code = warmUp.code,
            )
            else -> row(
                id = DeviceCheckId.Engine,
                status = CheckStatus.Unsupported,
                summary = errorMessage(DeviceErrorCode.WARMUP_FAILED),
                technical = technical,
                code = DeviceErrorCode.WARMUP_FAILED,
            )
        }
    }

    private fun assessStorage(capability: DeviceCapability): DeviceCheckRow {
        val technical = buildString {
            append("Storage bebas ${capability.storageFreeMb} MB")
            append("; notifikasi: ")
            append(if (capability.notificationPermissionGranted) "diizinkan" else "belum diizinkan")
            append("; Wi-Fi: ")
            append(if (capability.wifiConnected) "terhubung" else "belum terhubung")
        }
        return when {
            capability.storageFreeMb < MIN_STORAGE_MB -> row(
                id = DeviceCheckId.Storage,
                status = CheckStatus.Unsupported,
                summary = "Ruang penyimpanan ${capability.storageFreeMb} MB; minimal 1 GB",
                technical = technical,
                code = DeviceErrorCode.STORAGE_LOW,
            )
            capability.storageFreeMb < RECOMMENDED_STORAGE_MB -> row(
                id = DeviceCheckId.Storage,
                status = CheckStatus.Warning,
                summary = "Ruang penyimpanan ${capability.storageFreeMb} MB; sisakan minimal 2 GB",
                technical = technical,
            )
            !capability.notificationPermissionGranted -> row(
                id = DeviceCheckId.Storage,
                status = CheckStatus.Warning,
                summary = "Izin notifikasi belum diberikan; status pemantauan tidak terlihat saat layar mati",
                technical = technical,
            )
            !capability.wifiConnected -> row(
                id = DeviceCheckId.Storage,
                status = CheckStatus.Warning,
                summary = "Belum terhubung ke Wi-Fi; sambungkan saat mengatur kamera",
                technical = technical,
            )
            else -> row(
                id = DeviceCheckId.Storage,
                status = CheckStatus.Pass,
                summary = "Penyimpanan ${capability.storageFreeMb} MB bebas, izin dan Wi-Fi siap",
                technical = technical,
            )
        }
    }

    private fun row(
        id: DeviceCheckId,
        status: CheckStatus,
        summary: String,
        technical: String?,
        code: String? = null,
    ): DeviceCheckRow = DeviceCheckRow(
        id = id,
        status = status,
        summary = summary,
        technical = technical,
        errorCode = code,
    )
}
