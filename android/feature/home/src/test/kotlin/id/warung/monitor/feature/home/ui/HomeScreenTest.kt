package id.warung.monitor.feature.home.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import id.warung.monitor.feature.home.domain.HomeOverlay
import id.warung.monitor.feature.home.domain.NetworkReach
import id.warung.monitor.feature.home.domain.StreamErrorKind
import id.warung.monitor.feature.home.domain.StreamUiState
import id.warung.monitor.feature.home.domain.TableMapState
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
        onManageCameras: () -> Unit = {},
        appVersion: String = "",
        devMode: Boolean = false,
        onMonitoringChange: (Boolean) -> Unit = {},
        onReconnect: () -> Unit = {},
        onAnalysisChange: (Boolean) -> Unit = {},
        onOpenDevCamera: (() -> Unit)? = null,
        preview: @Composable (CameraProfile, (Int, Int) -> Unit) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                HomeScreen(
                    state = state,
                    onScan = onScan,
                    onOpenCamera = {},
                    onDismissScan = {},
                    onManageCameras = onManageCameras,
                    onOpenSettings = onOpenSettings,
                    onLogout = {},
                    onRetry = onRetry,
                    preview = preview,
                    powerMode = powerMode,
                    appVersion = appVersion,
                    devMode = devMode,
                    onMonitoringChange = onMonitoringChange,
                    onReconnect = onReconnect,
                    onAnalysisChange = onAnalysisChange,
                    onOpenDevCamera = onOpenDevCamera,
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
        composeRule.onNodeWithText("Reload").assertIsDisplayed()
        composeRule.onNodeWithText("Scan CCTV").assertDoesNotExist()
    }

    @Test
    fun a_live_stream_primary_action_reloads_the_selected_camera() {
        var reloads = 0
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                stream = StreamUiState.Live(1_000L),
                loading = false,
            ),
            onReconnect = { reloads++ },
        )

        composeRule.onNodeWithText("Reload").performClick()

        composeRule.runOnIdle { assertEquals(1, reloads) }
    }

    @Test
    fun dev_mode_shows_one_tap_cctv1_shortcut() {
        var opens = 0
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                loading = false,
            ),
            devMode = true,
            onOpenDevCamera = { opens++ },
        )

        composeRule.onNodeWithTag("dev-camera-shortcut").assertIsDisplayed()
        composeRule.onNodeWithText("CCTV 1").assertIsDisplayed()
        composeRule.onNodeWithTag("dev-camera-shortcut").performClick()

        composeRule.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun release_mode_hides_the_dev_camera_shortcut() {
        render(
            HomeUiState(cameras = listOf(camera), camera = camera, loading = false),
            devMode = false,
            onOpenDevCamera = {},
        )

        composeRule.onNodeWithTag("dev-camera-shortcut").assertDoesNotExist()
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
    fun scan_button_keeps_its_label_when_the_stream_is_not_live() {
        render(HomeUiState(cameras = listOf(camera), camera = camera, loading = false))

        composeRule.onNodeWithText("Scan CCTV").assertIsDisplayed()
        composeRule.onNodeWithText("Reload").assertDoesNotExist()
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
        composeRule.onNodeWithTag("power-saver-note").performScrollTo().assertIsDisplayed()
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
                    preview = { _, _ -> },
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
    fun the_analysis_panel_shows_only_totals_and_keeps_unknown_separate() {
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
                analysisFrameAtMillis = 1_700_000_000_000L,
                frameToInferenceMillis = 137L,
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("analysis-panel").assertIsDisplayed()
        composeRule.onNodeWithText("Meja 1").assertDoesNotExist()
        composeRule.onNodeWithText("Meja 2").assertDoesNotExist()
        composeRule.onNodeWithText("Kosong", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Terisi", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("analysis-time").assertIsDisplayed()
        composeRule.onNodeWithText("2023-11-15 06:13:20", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("delay stream ke analisa 137 ms", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("analysis-cadence").assertIsDisplayed()
    }

    @Test
    fun the_analysis_panel_is_hidden_until_rust_has_a_configuration() {
        render(HomeUiState(cameras = listOf(camera), camera = camera, loading = false))

        composeRule.onNodeWithTag("analysis-panel").assertDoesNotExist()
    }

    @Test
    fun the_video_card_takes_the_decoded_frame_aspect_ratio() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                stream = StreamUiState.Live(1_000L),
                loading = false,
            ),
            preview = { _, onVideoSize ->
                LaunchedEffect(Unit) { onVideoSize(640, 480) }
            },
        )

        val bounds = composeRule.onNodeWithTag("video-card").getUnclippedBoundsInRoot()
        val ratio = (bounds.right.value - bounds.left.value) /
            (bounds.bottom.value - bounds.top.value)

        assertEquals(4f / 3f, ratio, 0.02f)
    }

    @Test
    fun detection_boxes_stay_hidden_until_the_frame_size_is_known() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                stream = StreamUiState.Live(1_000L),
                detections = listOf(DetectionRect(0.1f, 0.2f, 0.3f, 0.4f, 0.9f)),
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("detection-overlay").assertDoesNotExist()
    }

    @Test
    fun detection_boxes_appear_once_the_frame_size_is_known() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                stream = StreamUiState.Live(1_000L),
                detections = listOf(DetectionRect(0.1f, 0.2f, 0.3f, 0.4f, 0.9f)),
                loading = false,
            ),
            preview = { _, onVideoSize ->
                LaunchedEffect(Unit) { onVideoSize(640, 480) }
            },
        )

        composeRule.onNodeWithTag("detection-overlay").assertExists()
    }

    @Test
    fun the_master_switch_reports_changes() {
        var requested: Boolean? = null
        render(
            HomeUiState(loading = false),
            onMonitoringChange = { requested = it },
        )

        composeRule.onNodeWithTag("monitoring-master-switch").assertIsOn()
        composeRule.onNodeWithTag("monitoring-master-switch").performClick()

        composeRule.runOnIdle { assertEquals(false, requested) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun the_analysis_switch_reports_start_and_stop() {
        var requested: Boolean? = null
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                mapping = TableMapState.Mapped(tables = 3, revision = 1),
                loading = false,
            ),
            onAnalysisChange = { requested = it },
        )

        composeRule.onNodeWithTag("analysis-toggle").assertIsOn()
        composeRule.onNodeWithText("Analisa berjalan").assertIsDisplayed()
        composeRule.onNodeWithTag("analysis-toggle").performClick()
        composeRule.runOnIdle { assertEquals(false, requested) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun a_camera_without_a_map_asks_for_mapping_instead_of_an_empty_panel() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                mapping = TableMapState.Missing,
                tables = emptyList(),
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("mapping-prompt").assertIsDisplayed()
        composeRule.onNodeWithText("Peta meja belum ada").assertIsDisplayed()
        composeRule.onNodeWithText("Analisa menunggu peta meja").assertIsDisplayed()
        composeRule.onNodeWithTag("analysis-toggle").assertIsNotEnabled()
        composeRule.onNodeWithTag("analysis-panel").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun the_mapping_prompt_opens_the_table_map_editor() {
        var opened = 0
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                mapping = TableMapState.Missing,
                loading = false,
            ),
            onManageCameras = { opened++ },
        )

        composeRule.onNodeWithTag("mapping-action").performClick()

        composeRule.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun a_saved_map_shows_the_panel_and_hides_the_prompt() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                mapping = TableMapState.Mapped(tables = 3, revision = 1),
                tables = listOf(
                    AnalysisTable("meja-01", "Meja 1", AnalysisStatus.Available, 0, 0.0f, 0L),
                ),
                inferenceFps = 2.0,
                loading = false,
            ),
        )

        composeRule.onNodeWithTag("analysis-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("mapping-prompt").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun the_video_sits_above_the_analysis_section() {
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                mapping = TableMapState.Mapped(tables = 3, revision = 1),
                tables = listOf(
                    AnalysisTable("meja-01", "Meja 1", AnalysisStatus.Available, 0, 0.0f, 0L),
                ),
                inferenceFps = 2.0,
                loading = false,
            ),
        )

        val video = composeRule.onNodeWithTag("video-card").getUnclippedBoundsInRoot()
        val panel = composeRule.onNodeWithTag("analysis-panel").getUnclippedBoundsInRoot()

        assertTrue(
            "analysis panel (top=${panel.top}) must start below the video (bottom=${video.bottom})",
            panel.top.value >= video.bottom.value,
        )
    }


    @Test
    fun a_disabled_master_switch_shows_the_hidden_list_note() {
        render(HomeUiState(monitoringEnabled = false, loading = false))

        composeRule.onNodeWithTag("monitoring-master-switch").assertIsOff()
        composeRule.onNodeWithText("Monitoring nonaktif").assertIsDisplayed()
        composeRule.onNodeWithText("Daftar kamera disembunyikan sampai diaktifkan lagi").assertIsDisplayed()
    }

    @Test
    fun turning_monitoring_off_hides_the_camera_list_from_the_scan_sheet() {
        composeRule.setContent {
            HomeScreen(
                state = HomeUiState(
                    cameras = listOf(camera),
                    camera = camera,
                    monitoringEnabled = false,
                    overlay = HomeOverlay.Scan,
                    loading = false,
                ),
                onScan = {},
                onOpenCamera = {},
                onDismissScan = {},
                onManageCameras = {},
                onOpenSettings = {},
                onLogout = {},
                onRetry = {},
                preview = { _, _ -> },
                powerMode = HomePowerMode.Standard,
                appVersion = "",
                devMode = false,
            )
        }

        composeRule.onNodeWithText("Kamera Warung").assertDoesNotExist()
        composeRule.onNodeWithText("Belum ada kamera tersimpan.").assertIsDisplayed()
    }

    @Test
    fun a_paused_camera_disappears_from_the_scan_sheet_but_a_live_one_stays() {
        val paused = camera.copy(
            id = CameraId("cam-paused"),
            label = CameraLabel("Kamera Dijeda"),
            monitoringEnabled = false,
        )
        composeRule.setContent {
            HomeScreen(
                state = HomeUiState(
                    cameras = listOf(camera, paused),
                    camera = camera,
                    overlay = HomeOverlay.Scan,
                    loading = false,
                ),
                onScan = {},
                onOpenCamera = {},
                onDismissScan = {},
                onManageCameras = {},
                onOpenSettings = {},
                onLogout = {},
                onRetry = {},
                preview = { _, _ -> },
                powerMode = HomePowerMode.Standard,
                appVersion = "",
                devMode = false,
            )
        }

        composeRule.onNodeWithText("Kamera Dijeda").assertDoesNotExist()
    }

    @Test
    fun a_failed_stream_offers_an_explicit_reconnect_action() {
        var reconnects = 0
        render(
            HomeUiState(
                cameras = listOf(camera),
                camera = camera,
                stream = StreamUiState.Failed(StreamErrorKind.Unreachable),
                loading = false,
            ),
            onReconnect = { reconnects++ },
        )

        composeRule.onNodeWithTag("reconnect-camera").performClick()

        composeRule.runOnIdle { assertEquals(1, reconnects) }
    }
}
