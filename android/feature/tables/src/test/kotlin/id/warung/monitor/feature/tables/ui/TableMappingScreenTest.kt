package id.warung.monitor.feature.tables.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import id.warung.monitor.core.model.MappingFrame
import id.warung.monitor.core.model.NormalizedPoint
import id.warung.monitor.core.model.TableId
import id.warung.monitor.feature.tables.domain.TableDraft
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class TableMappingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val validTable = TableDraft(
        id = TableId("meja-01"),
        label = "Meja 01",
        points = listOf(
            NormalizedPoint(0.1, 0.1),
            NormalizedPoint(0.3, 0.1),
            NormalizedPoint(0.3, 0.3),
            NormalizedPoint(0.1, 0.3),
        ),
    )

    private val emptyTable = TableDraft(
        id = TableId("meja-02"),
        label = "Meja 02",
        points = listOf(NormalizedPoint(0.5, 0.5), NormalizedPoint(0.6, 0.6)),
    )

    private fun state(
        tables: List<TableDraft> = listOf(validTable),
        configuredFrame: MappingFrame? = null,
        frame: MappingFrame = MappingFrame(1280, 720, "16:9"),
    ) = TableMappingState.Initial.copy(
        cameraId = "camera-default",
        cameraLabel = "Kamera Utama",
        frame = frame,
        configuredFrame = configuredFrame,
        tables = tables,
        message = null,
    )

    private fun render(
        state: TableMappingState,
        onSave: () -> Unit = {},
        onAddTable: () -> Unit = {},
        onLabelChange: (TableId, String) -> Unit = { _, _ -> },
        onResetMap: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            TableMappingScreen(
                state = state,
                onBack = onBack,
                onAddTable = onAddTable,
                onFinishDrawing = {},
                onCancelDrawing = {},
                onSelectTable = {},
                onSelectVertex = {},
                onAddPoint = {},
                onMoveVertex = { _, _, _ -> },
                onRemoveVertex = { _, _ -> },
                onRemoveTable = {},
                onLabelChange = onLabelChange,
                onUndo = {},
                onRedo = {},
                onSave = onSave,
                onResetMap = onResetMap,
                onRetryEngine = {},
                onDismissMessage = {},
            )
        }
    }

    @Test
    fun save_button_is_enabled_when_every_table_is_valid_and_calls_on_save() {
        var saved = 0
        render(state(tables = listOf(validTable)), onSave = { saved++ })

        composeRule.onNodeWithTag("save-map").assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(1, saved) }
    }

    @Test
    fun save_button_stays_disabled_while_a_table_is_invalid() {
        render(state(tables = listOf(validTable, emptyTable)))

        composeRule.onNodeWithTag("save-map").assertIsNotEnabled()
        composeRule.onNodeWithText("1/2 meja siap").assertExists()
    }

    @Test
    fun status_text_reports_ready_count_for_an_empty_map() {
        render(state(tables = emptyList()))

        composeRule.onNodeWithText("Belum ada meja").assertExists()
        composeRule.onNodeWithTag("save-map").assertIsNotEnabled()
    }

    @Test
    fun table_card_shows_the_reason_it_is_not_valid() {
        render(state(tables = listOf(emptyTable)))

        composeRule.onNodeWithTag("label-meja-02").assertExists()
        composeRule.onNodeWithText("Poligon minimal 3 titik", substring = true).assertExists()
    }

    @Test
    fun editing_the_label_reports_table_id_and_new_text() {
        var editedId: TableId? = null
        var editedText: String? = null
        render(
            state(tables = listOf(validTable)),
            onLabelChange = { id, text ->
                editedId = id
                editedText = text
            },
        )

        composeRule.onNodeWithTag("label-meja-01").performTextReplacement("Meja Depan")

        composeRule.runOnIdle {
            assertEquals(TableId("meja-01"), editedId)
            assertEquals("Meja Depan", editedText)
        }
    }

    @Test
    fun recalibration_banner_appears_when_the_live_ratio_differs() {
        var reset = 0
        render(
            state(
                tables = listOf(validTable),
                configuredFrame = MappingFrame(1280, 720, "16:9"),
                frame = MappingFrame(640, 480, "4:3"),
            ),
            onResetMap = { reset++ },
        )

        composeRule.onNodeWithText("Peta dibuat untuk 16:9", substring = true).assertExists()
        composeRule.onNodeWithText("Mulai ulang").performClick()
        composeRule.runOnIdle { assertEquals(1, reset) }
    }

    @Test
    fun recalibration_banner_is_absent_when_the_ratio_matches() {
        render(
            state(
                tables = listOf(validTable),
                configuredFrame = MappingFrame(1280, 720, "16:9"),
                frame = MappingFrame(1920, 1080, "16:9"),
            ),
        )

        composeRule.onNodeWithText("Peta dibuat untuk", substring = true).assertDoesNotExist()
    }

    @Test
    fun add_table_click_calls_the_callback() {
        var clicks = 0
        render(state(tables = emptyList()), onAddTable = { clicks++ })

        composeRule.onNodeWithTag("add-table").performClick()

        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-mdpi")
    fun wide_layout_shows_canvas_and_panel_together() {
        render(state(tables = listOf(validTable)))

        composeRule.onNodeWithTag("mapping-canvas").assertExists()
        composeRule.onNodeWithTag("mapping-panel").assertExists()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-mdpi")
    fun wide_layout_can_still_save() {
        var saved = 0
        render(state(tables = listOf(validTable)), onSave = { saved++ })

        composeRule.onNodeWithTag("save-map").performClick()

        composeRule.runOnIdle { assertEquals(1, saved) }
    }
}
