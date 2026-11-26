package id.warung.monitor.feature.onboarding.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAssessmentTest {

    private fun capability(
        apiLevel: Int = 33,
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        ramTotalMb: Int = 4_096,
        ramAvailableMb: Int = 1_500,
        storageFreeMb: Int = 4_096,
        modelBundled: Boolean = true,
        nativeLoadable: Boolean = true,
        notifications: Boolean = true,
        wifi: Boolean = true,
    ) = DeviceCapability(
        apiLevel = apiLevel,
        abis = abis,
        ramTotalMb = ramTotalMb,
        ramAvailableMb = ramAvailableMb,
        storageFreeMb = storageFreeMb,
        modelBundled = modelBundled,
        nativeLibraryLoadable = nativeLoadable,
        notificationPermissionGranted = notifications,
        wifiConnected = wifi,
        appVersionCode = 7,
        appVersionName = "0.1.0",
    )

    private fun row(report: DeviceReport, id: DeviceCheckId) =
        report.rows.first { it.id == id }

    @Test
    fun modern_phone_with_warm_up_gets_full_profile() {
        val report = DeviceAssessment.assess(capability(), WarmUpResult.Passed(120))

        assertEquals(DeviceProfile.Full, report.profile)
        assertTrue(report.engineReady)
        assertFalse(report.hasWarning)
        assertEquals(CheckStatus.Pass, row(report, DeviceCheckId.Platform).status)
        assertEquals(CheckStatus.Pass, row(report, DeviceCheckId.Engine).status)
    }

    @Test
    fun four_gigabyte_device_on_android_11_gets_balanced_not_full() {
        val report = DeviceAssessment.assess(
            capability(apiLevel = 30, ramTotalMb = 4_096),
            WarmUpResult.Passed(80),
        )

        assertEquals(DeviceProfile.Balanced, report.profile)
        assertEquals(CheckStatus.Warning, row(report, DeviceCheckId.Platform).status)
    }

    @Test
    fun three_gigabyte_device_gets_balanced_profile_with_warning() {
        val report = DeviceAssessment.assess(
            capability(ramTotalMb = 3_072),
            WarmUpResult.Passed(90),
        )

        assertEquals(DeviceProfile.Balanced, report.profile)
        assertTrue(report.hasWarning)
        assertEquals(CheckStatus.Warning, row(report, DeviceCheckId.Memory).status)
        assertFalse(report.isBlocked)
    }

    @Test
    fun two_gigabyte_device_is_blocked_before_warm_up() {
        val report = DeviceAssessment.assess(
            capability(ramTotalMb = 2_048),
            WarmUpResult.Skipped,
        )

        assertEquals(DeviceProfile.Blocked, report.profile)
        assertEquals(DeviceErrorCode.RAM_LOW, report.blockedCode)
        assertEquals(CheckStatus.Unsupported, row(report, DeviceCheckId.Memory).status)
        assertEquals(CheckStatus.Skipped, row(report, DeviceCheckId.Engine).status)
        assertFalse(report.engineReady)
    }

    @Test
    fun thirty_two_bit_abi_is_unsupported_and_skips_the_engine() {
        val report = DeviceAssessment.assess(
            capability(abis = listOf("armeabi-v7a")),
            WarmUpResult.Skipped,
        )

        assertEquals(DeviceProfile.Blocked, report.profile)
        assertEquals(DeviceErrorCode.ABI_UNSUPPORTED, report.blockedCode)
        assertEquals(CheckStatus.Unsupported, row(report, DeviceCheckId.Platform).status)
        assertEquals(CheckStatus.Skipped, row(report, DeviceCheckId.Engine).status)
    }

    @Test
    fun api_below_29_is_unsupported() {
        val report = DeviceAssessment.assess(
            capability(apiLevel = 28),
            WarmUpResult.Skipped,
        )

        assertEquals(DeviceProfile.Blocked, report.profile)
        assertEquals(DeviceErrorCode.ANDROID_TOO_OLD, report.blockedCode)
        assertEquals(CheckStatus.Skipped, row(report, DeviceCheckId.Engine).status)
    }

    @Test
    fun native_load_failure_is_unsupported_with_stable_code() {
        val report = DeviceAssessment.assess(
            capability(nativeLoadable = false),
            WarmUpResult.Skipped,
        )

        assertEquals(DeviceProfile.Blocked, report.profile)
        assertEquals(DeviceErrorCode.LIBRARY_LOAD, report.blockedCode)
        assertEquals(CheckStatus.Unsupported, row(report, DeviceCheckId.Engine).status)
    }

    @Test
    fun missing_model_is_a_warning_and_downgrades_to_lite() {
        val report = DeviceAssessment.assess(
            capability(modelBundled = false),
            WarmUpResult.Skipped,
        )

        assertEquals(DeviceProfile.Lite, report.profile)
        assertEquals(CheckStatus.Skipped, row(report, DeviceCheckId.Engine).status)
        assertFalse(report.isBlocked)
        assertTrue(report.hasWarning)
    }

    @Test
    fun failed_warm_up_is_unsupported_even_when_memory_is_large() {
        val report = DeviceAssessment.assess(
            capability(),
            WarmUpResult.Failed(DeviceErrorCode.ONNX_INIT, "runtime rejected"),
        )

        assertEquals(DeviceProfile.Blocked, report.profile)
        assertEquals(DeviceErrorCode.ONNX_INIT, report.blockedCode)
        assertEquals(CheckStatus.Unsupported, row(report, DeviceCheckId.Engine).status)
    }

    @Test
    fun low_storage_is_unsupported_before_permissions_are_considered() {
        val report = DeviceAssessment.assess(
            capability(storageFreeMb = 512, notifications = false),
            WarmUpResult.Passed(100),
        )

        assertEquals(DeviceProfile.Blocked, report.profile)
        assertEquals(DeviceErrorCode.STORAGE_LOW, report.blockedCode)
        assertEquals(CheckStatus.Unsupported, row(report, DeviceCheckId.Storage).status)
    }

    @Test
    fun missing_notification_permission_only_warns() {
        val report = DeviceAssessment.assess(
            capability(notifications = false),
            WarmUpResult.Passed(100),
        )

        assertEquals(CheckStatus.Warning, row(report, DeviceCheckId.Storage).status)
        assertEquals(DeviceProfile.Full, report.profile)
    }

}
