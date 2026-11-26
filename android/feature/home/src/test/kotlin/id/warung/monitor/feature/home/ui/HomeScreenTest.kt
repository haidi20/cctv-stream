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
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.TableSummary
import id.warung.monitor.feature.home.domain.HomePowerMode
import id.warung.monitor.feature.home.domain.HomeUiState
import id.warung.monitor.feature.home.domain.StreamErrorKind
import id.warung.monitor.feature.home.domain.StreamUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val camera = CameraProfile(
        id = CameraId("cam-1"),
        label = CameraLabel("Kamera Warung"),
        source = CameraSource.Demo,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun render(
        state: HomeUiState,
        onScan: () -> Unit = {},
        onRetry: () -> Unit = {},
        powerMode: HomePowerMode = HomePowerMode.Standard,
    ) {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                HomeScreen(
                    state = state,
                    onScan = onScan,
                    onOpenCamera = {},
                    onDismissScan = {},
                    onManageCameras = {},
                    onLogout = {},
                    onRetry = onRetry,
                    preview = { },
                    powerMode = powerMode,
                )
            }
        }
    }

    @Test
    fun both_total_cards_are_visible_with_the_scan_cta() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = TableSummary(available = 3, occupied = 2, unknown = 0),
                stream = StreamUiState.Live(1_000L),
                demoMode = true,
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("count-Kosong").assertIsDisplayed()
        composeRule.onNodeWithTag("count-Terisi").assertIsDisplayed()
        composeRule.onNodeWithTag("scan-button").assertIsDisplayed()
        composeRule.onNodeWithText("Scan CCTV").assertIsDisplayed()
    }

    @Test
    fun idle_state_shows_an_em_dash_instead_of_zero() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = null,
                stream = StreamUiState.Idle,
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("count-Kosong").assertIsDisplayed()
        composeRule.onAllNodesWithText("—").assertCountEquals(2)
        composeRule.onNodeWithText("Status meja belum tersedia").assertIsDisplayed()
    }

    @Test
    fun unknown_tables_are_called_out_separately() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = TableSummary(available = 4, occupied = 1, unknown = 2),
                stream = StreamUiState.Live(1_000L),
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("unknown-note").assertIsDisplayed()
        composeRule.onNodeWithText("2 meja tidak terpantau").assertIsDisplayed()
    }

    @Test
    fun failed_stream_offers_retry_and_hides_the_live_chip() {
        var retries = 0
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = null,
                stream = StreamUiState.Failed(StreamErrorKind.Unreachable),
                loading = false,
            ),
            onRetry = { retries++ },
        )

        composeRule.onAllNodesWithText("Kamera tidak terjangkau").assertCountEquals(2)
        composeRule.onNodeWithText("Coba lagi").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        composeRule.onNodeWithText("LIVE").assertDoesNotExist()
    }

    @Test
    fun the_reconnecting_state_names_the_attempt_instead_of_showing_a_dead_frame() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = null,
                stream = StreamUiState.Reconnecting(2, 2_000L),
                loading = false,
            ),
        )

        composeRule.onNodeWithText("Menyambung ulang (percobaan 2, 2 dtk)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("LIVE").assertDoesNotExist()
    }

    @Test
    fun scan_button_fires_the_callback() {
        var scans = 0
        render(HomeUiState(cameras = listOf(camera), camera = camera, loading = false), onScan = { scans++ })

        composeRule.onNodeWithTag("scan-button").performClick()

        composeRule.runOnIdle { assertEquals(1, scans) }
    }

    @Test
    @Config(qualifiers = "w720dp-h411dp-mdpi")
    fun a_720dp_window_uses_the_two_panel_layout() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = TableSummary(available = 1, occupied = 1, unknown = 0),
                stream = StreamUiState.Live(1_000L),
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("home-wide").assertIsDisplayed()
        composeRule.onNodeWithTag("home-compact").assertDoesNotExist()
        composeRule.onNodeWithTag("scan-button").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun a_411dp_window_uses_the_single_column() {
        render(HomeUiState(cameras = listOf(camera), camera = camera, loading = false))

        composeRule.onNodeWithTag("home-compact").assertIsDisplayed()
        composeRule.onNodeWithTag("home-wide").assertDoesNotExist()
    }

    @Test
    fun a_lite_device_sees_the_saver_note() {
        render(
            HomeUiState(cameras = listOf(camera), camera = camera, loading = false),
            powerMode = HomePowerMode.Saver,
        )

        composeRule.onNodeWithTag("power-saver-note").assertIsDisplayed()
        composeRule.onNodeWithText("Mode hemat: deteksi lebih lambat.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun a_standard_device_does_not_see_the_saver_note() {
        render(
            HomeUiState(cameras = listOf(camera), camera = camera, loading = false),
            powerMode = HomePowerMode.Standard,
        )

        composeRule.onNodeWithTag("power-saver-note").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w720dp-h411dp-mdpi")
    fun the_saver_note_also_appears_in_the_two_panel_layout() {
        render(
            HomeUiState(cameras = listOf(camera), camera = camera, loading = false),
            powerMode = HomePowerMode.Saver,
        )

        composeRule.onNodeWithTag("home-wide").assertIsDisplayed()
        composeRule.onNodeWithTag("power-saver-note").assertIsDisplayed()
    }
}

