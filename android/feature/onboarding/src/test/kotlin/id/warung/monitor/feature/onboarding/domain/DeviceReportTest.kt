package id.warung.monitor.feature.onboarding.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReportTest {

    private val report = DeviceReport(
        profile = DeviceProfile.Blocked,
        rows = listOf(
            DeviceCheckRow(
                id = DeviceCheckId.Platform,
                status = CheckStatus.Unsupported,
                summary = "Prosesor belum didukung",
                technical = "Koneksi rtsp://admin:pass@192.168.1.10/live gagal dibaca; " +
                    "password=hunter2 token=abc123",
                errorCode = DeviceErrorCode.ABI_UNSUPPORTED,
            ),
        ),
        engineReady = false,
        blockedCode = DeviceErrorCode.ABI_UNSUPPORTED,
        appVersionCode = 12,
        appVersionName = "0.1.0",
    )

    @Test
    fun clipboard_report_contains_profile_and_error_code() {
        val text = report.toClipboardText()

        assertTrue(text.contains("Tidak didukung"))
        assertTrue(text.contains(DeviceErrorCode.ABI_UNSUPPORTED))
        assertTrue(text.contains("0.1.0"))
    }

    @Test
    fun clipboard_report_redacts_rtsp_urls_and_credentials() {
        val text = report.toClipboardText()

        assertFalse(text.contains("rtsp://"))
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("192.168.1.10"))
        assertFalse(text.contains("password="))
    }

    @Test
    fun blocked_reason_comes_from_the_unsupported_row() {
        assertTrue(report.blockedReason == "Prosesor belum didukung")
        assertTrue(report.isBlocked)
    }

    @Test
    fun tone_mapping_keeps_warning_amber_and_unsupported_distinct() {
        assertTrue(toneOf(CheckStatus.Warning) == CheckTone.Caution)
        assertTrue(toneOf(CheckStatus.Unsupported) == CheckTone.Blocked)
        assertTrue(toneOf(CheckStatus.Pass) == CheckTone.Positive)
        assertTrue(toneOf(CheckStatus.Skipped) == CheckTone.Muted)
    }
}