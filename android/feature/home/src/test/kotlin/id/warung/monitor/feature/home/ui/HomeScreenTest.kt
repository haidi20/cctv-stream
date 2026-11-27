package id.warung.monitor.feature.home.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.TableSummary
import id.warung.monitor.feature.home.domain.HomePowerMode
import id.warung.monitor.feature.home.domain.AnalysisStatus
import id.warung.monitor.feature.home.domain.AnalysisTable
import id.warung.monitor.feature.home.domain.DetectionRect
import id.warung.monitor.feature.home.domain.HomeUiState
import id.warung.monitor.feature.home.domain.NetworkReach
import id.warung.monitor.feature.home.domain.StreamErrorKind
import id.warung.monitor.feature.home.domain.StreamUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        onOpenSettings: () -> Unit = {},
        appVersion: String = "",
        devMode: Boolean = false,
    ) {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                HomeScreen(
                    state = state,
                    onScan = onScan,
                    onOpenCamera = {},
                    onDismissScan = {},
                    onManageCameras = {},
                    onOpenSettings = onOpenSettings,
                    onLogout = {},
                    onRetry = onRetry,
                    preview = { },
                    powerMode = powerMode,
                    appVersion = appVersion,
                    devMode = devMode,
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
    fun cellular_only_shows_the_persistent_offline_note() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                stream = StreamUiState.Idle,
                network = NetworkReach.CellularOnly,
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("offline-banner").assertIsDisplayed()
        composeRule.onNodeWithText("pindah ke Wi-Fi warung", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("back-online-banner").assertDoesNotExist()
    }

    @Test
    fun no_wifi_shows_the_persistent_offline_note() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                stream = StreamUiState.Idle,
                network = NetworkReach.Offline,
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("offline-banner").assertIsDisplayed()
        composeRule.onNodeWithText("Wi-Fi belum tersambung", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("back-online-banner").assertDoesNotExist()
    }

    @Test
    fun regaining_wifi_shows_the_back_online_note() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = TableSummary(available = 1, occupied = 1, unknown = 0),
                stream = StreamUiState.Live(1_000L),
                network = NetworkReach.WifiPrivate,
                justReconnected = true,
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("back-online-banner").assertIsDisplayed()
        composeRule.onNodeWithText("Anda kembali online").assertIsDisplayed()
        composeRule.onNodeWithTag("offline-banner").assertDoesNotExist()
    }

    @Test
    fun the_back_online_toast_sits_at_the_top_of_the_screen() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = TableSummary(available = 1, occupied = 1, unknown = 0),
                stream = StreamUiState.Live(1_000L),
                network = NetworkReach.WifiPrivate,
                justReconnected = true,
                loading = false,
            ),
        )

        val toastTop = composeRule.onNodeWithTag("back-online-banner")
            .fetchSemanticsNode().boundsInRoot.top
        val screenTop = composeRule.onNodeWithTag("home-compact")
            .fetchSemanticsNode().boundsInRoot.top

        assertTrue(
            "the transient note must float at the top, not inside the scroll column",
            toastTop <= screenTop + 80f,
        )
    }

    @Test
    fun an_online_stream_without_a_reconnect_shows_no_transport_note() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = TableSummary(available = 1, occupied = 1, unknown = 0),
                stream = StreamUiState.Live(1_000L),
                network = NetworkReach.WifiPrivate,
                justReconnected = false,
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("offline-banner").assertDoesNotExist()
        composeRule.onNodeWithTag("back-online-banner").assertDoesNotExist()
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

    @Test
    fun a_changed_video_ratio_shows_the_recalibration_notice() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = TableSummary(available = 1, occupied = 1, unknown = 0),
                stream = StreamUiState.Live(1_000L),
                needsRecalibration = true,
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("recalibration-notice").assertIsDisplayed()
    }

    @Test
    fun the_recalibration_notice_opens_the_mapping_screen() {
        var opened = 0
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                HomeScreen(
                    state = HomeUiState(
                        cameras = listOf(camera),
                        camera = camera,
                        summary = TableSummary(available = 1, occupied = 1, unknown = 0),
                        stream = StreamUiState.Live(1_000L),
                        needsRecalibration = true,
                        loading = false,
                    ),
                    onScan = {},
                    onOpenCamera = {},
                    onDismissScan = {},
                    onManageCameras = { opened += 1 },
                    onOpenSettings = {},
                    onLogout = {},
                    onRetry = {},
                    preview = { },
                )
            }
        }

        composeRule.onNodeWithTag("recalibration-action").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun a_calibrated_stream_hides_the_recalibration_notice() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = TableSummary(available = 1, occupied = 1, unknown = 0),
                stream = StreamUiState.Live(1_000L),
                needsRecalibration = false,
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("recalibration-notice").assertDoesNotExist()
    }

    @Test
    fun a_wrong_password_is_named_instead_of_the_generic_stream_error() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                stream = StreamUiState.Failed(StreamErrorKind.AuthFailed),
                loading = false,
            ),
        )

        // The message appears in the video card and again in the status line.
        composeRule.onAllNodesWithText("Kata sandi kamera salah").assertCountEquals(2)
        composeRule.onNodeWithText("Stream gagal").assertDoesNotExist()
    }

    @Test
    fun the_overflow_menu_opens_the_sync_settings_screen() {
        var opened = 0
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = TableSummary(available = 1, occupied = 0, unknown = 0),
                stream = StreamUiState.Live(1_000L),
                loading = false,
            ),
            onOpenSettings = { opened += 1 },
        )

        composeRule.onNodeWithContentDescription("Menu lain").performClick()
        composeRule.onNodeWithText("Sinkronisasi server").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun the_app_version_is_visible_to_the_user() {
        render(
            HomeUiState(cameras = listOf(camera), camera = camera, loading = false),
            appVersion = "0.2.0-dev",
        )

        composeRule.onNodeWithTag("app-version").assertIsDisplayed()
        composeRule.onNodeWithText("v0.2.0-dev").assertIsDisplayed()
    }

    @Test
    fun a_release_build_without_a_version_shows_no_badge() {
        render(HomeUiState(cameras = listOf(camera), camera = camera, loading = false))

        composeRule.onNodeWithTag("app-version").assertDoesNotExist()
    }

    @Test
    fun debug_builds_show_the_devel_label() {
        render(
            HomeUiState(cameras = listOf(camera), camera = camera, loading = false),
            appVersion = "0.2.0-dev",
            devMode = true,
        )

        composeRule.onNodeWithTag("devel-badge").assertIsDisplayed()
        composeRule.onNodeWithText("devel").assertIsDisplayed()
    }

    @Test
    fun release_builds_do_not_show_the_devel_label() {
        render(
            HomeUiState(cameras = listOf(camera), camera = camera, loading = false),
            devMode = false,
        )

        composeRule.onNodeWithTag("devel-badge").assertDoesNotExist()
    }

    @Test
    fun the_analysis_panel_lists_each_table_and_keeps_unknown_separate() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                summary = TableSummary(available = 1, occupied = 1, unknown = 1),
                stream = StreamUiState.Live(1_000L),
                tables = listOf(
                    AnalysisTable("meja-01", "Meja 1", AnalysisStatus.Available, 0, 0f, 0L),
                    AnalysisTable("meja-02", "Meja 2", AnalysisStatus.Occupied, 2, 0.8f, 0L),
                    AnalysisTable("meja-03", "Meja 3", AnalysisStatus.Unknown, 0, 0f, 0L),
                ),
                detections = listOf(DetectionRect(0.1f, 0.2f, 0.3f, 0.4f, 0.9f)),
                inferenceFps = 2.0,
                lastAnalysisAgeMillis = 500L,
                lastAnalysisMillis = 49_500L,
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("analysis-panel").assertIsDisplayed()
        composeRule.onNodeWithText("Meja 1").assertIsDisplayed()
        composeRule.onNodeWithText("Meja 2").assertIsDisplayed()
        composeRule.onNodeWithText("2 orang").assertIsDisplayed()
        composeRule.onNodeWithText("tidak terpantau").assertIsDisplayed()
        composeRule.onNodeWithText("kosong").assertIsDisplayed()
        composeRule.onNodeWithTag("analysis-time").assertIsDisplayed()
        composeRule.onNodeWithTag("analysis-cadence").assertIsDisplayed()
    }

    @Test
    fun the_analysis_panel_is_hidden_until_rust_has_a_configuration() {
        render(HomeUiState(cameras = listOf(camera), camera = camera, loading = false))

        composeRule.onNodeWithTag("analysis-panel").assertDoesNotExist()
    }

    @Test
    fun person_boxes_draw_over_the_video_only_when_present() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                stream = StreamUiState.Live(1_000L),
                detections = listOf(DetectionRect(0.1f, 0.2f, 0.3f, 0.4f, 0.9f)),
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("detection-overlay").assertIsDisplayed()
    }

    @Test
    fun an_empty_detection_list_draws_no_overlay() {
        render(HomeUiState(cameras = listOf(camera), camera = camera, loading = false))

        composeRule.onNodeWithTag("detection-overlay").assertDoesNotExist()
    }
}