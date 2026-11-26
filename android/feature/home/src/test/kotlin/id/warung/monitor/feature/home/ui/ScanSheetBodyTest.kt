package id.warung.monitor.feature.home.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.network.DiscoverySource
import id.warung.monitor.feature.cameras.ui.ScanRow
import id.warung.monitor.feature.cameras.ui.ScanUiState
import id.warung.monitor.feature.home.domain.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sheet body is rendered without ModalBottomSheet so these tests assert
 * discovery states, not sheet animation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class ScanSheetBodyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val saved = CameraProfile(
        id = CameraId("cam-1"),
        label = CameraLabel("Kamera Depan"),
        source = CameraSource.Rtsp(
            host = "192.168.1.64",
            port = 554,
            path = "/h264/ch1/sub/av_stream",
        ),
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun render(
        scanState: ScanUiState,
        state: HomeUiState = HomeUiState(cameras = listOf(saved), camera = null, loading = false),
        onOpenSaved: (CameraProfile) -> Unit = {},
        onRetryScan: () -> Unit = {},
        onManageCameras: () -> Unit = {},
    ) {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                ScanSheetBody(
                    state = state,
                    scanState = scanState,
                    onOpenSaved = onOpenSaved,
                    onRetryScan = onRetryScan,
                    onManageCameras = onManageCameras,
                )
            }
        }
    }

    @Test
    fun scanning_state_shows_progress_and_keeps_saved_cameras_listed() {
        render(ScanUiState.Scanning)

        composeRule.onNodeWithTag("scan-progress").assertIsDisplayed()
        composeRule.onNodeWithText("Mencari kamera di Wi-Fi ini...").assertIsDisplayed()
        composeRule.onNodeWithText("Kamera Depan").assertIsDisplayed()
    }

    @Test
    fun no_wifi_state_explains_the_refusal_and_offers_manual_entry() {
        var manual = 0
        render(ScanUiState.NoWifi, onManageCameras = { manual++ })

        composeRule.onNodeWithTag("scan-no-wifi").assertIsDisplayed()
        composeRule.onNodeWithText("Sambungkan ke Wi-Fi dulu").assertIsDisplayed()
        composeRule.onNodeWithText("Tambah manual").performClick()
        composeRule.runOnIdle { assertEquals(1, manual) }
    }

    @Test
    fun empty_results_offer_a_retry() {
        var retries = 0
        render(ScanUiState.Results(rows = emptyList(), fromCache = false), onRetryScan = { retries++ })

        composeRule.onNodeWithTag("scan-empty").assertIsDisplayed()
        composeRule.onNodeWithTag("scan-retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun failed_state_shows_the_message() {
        render(ScanUiState.Failed("Scan gagal. Periksa Wi-Fi lalu coba lagi."))

        composeRule.onNodeWithTag("scan-failed").assertIsDisplayed()
        composeRule.onNodeWithText("Scan gagal. Periksa Wi-Fi lalu coba lagi.").assertIsDisplayed()
    }

    @Test
    fun a_discovered_row_that_is_already_saved_opens_that_profile() {
        var opened: CameraProfile? = null
        render(
            scanState = ScanUiState.Results(
                rows = listOf(
                    ScanRow(
                        host = "192.168.1.64",
                        port = 554,
                        brand = "EZVIZ",
                        source = DiscoverySource.Saved,
                        alreadySaved = true,
                    ),
                ),
                fromCache = false,
            ),
            onOpenSaved = { opened = it },
        )

        composeRule.onNodeWithTag("discovered-192.168.1.64:554").performClick()
        composeRule.runOnIdle { assertEquals(saved.id, opened?.id) }
    }

    @Test
    fun an_unknown_row_routes_to_manual_camera_management() {
        var manual = 0
        render(
            scanState = ScanUiState.Results(
                rows = listOf(
                    ScanRow(
                        host = "192.168.1.72",
                        port = 554,
                        brand = null,
                        source = DiscoverySource.OpenPort,
                        alreadySaved = false,
                    ),
                ),
                fromCache = false,
            ),
            onManageCameras = { manual++ },
        )

        composeRule.onNodeWithText("192.168.1.72:554").assertIsDisplayed()
        composeRule.onNodeWithText("Baru").assertIsDisplayed()
        composeRule.onNodeWithTag("discovered-192.168.1.72:554").performClick()
        composeRule.runOnIdle { assertEquals(1, manual) }
    }

    @Test
    fun a_saved_camera_found_by_discovery_is_listed_once() {
        render(
            scanState = ScanUiState.Results(
                rows = listOf(
                    ScanRow(
                        host = "192.168.1.64",
                        port = 554,
                        brand = null,
                        source = DiscoverySource.Saved,
                        alreadySaved = true,
                    ),
                ),
                fromCache = false,
            ),
        )

        composeRule.onAllNodesWithText("Kamera Depan").assertCountEquals(1)
        composeRule.onNodeWithText("Kamera tersimpan").assertDoesNotExist()
    }

    @Test
    fun cached_results_are_labelled_as_such() {
        render(
            scanState = ScanUiState.Results(
                rows = listOf(
                    ScanRow(
                        host = "192.168.1.72",
                        port = 554,
                        brand = "Hikvision",
                        source = DiscoverySource.Onvif,
                        alreadySaved = false,
                    ),
                ),
                fromCache = true,
            ),
        )

        composeRule.onNodeWithTag("scan-from-cache").assertIsDisplayed()
        composeRule.onNodeWithText("Hikvision - ONVIF").assertIsDisplayed()
    }
}