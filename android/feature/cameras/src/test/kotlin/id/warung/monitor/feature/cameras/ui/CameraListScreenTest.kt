package id.warung.monitor.feature.cameras.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraLabel
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class CameraListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val front = CameraProfile(
        id = CameraId("cam-depan"),
        label = CameraLabel("Warung Depan"),
        source = CameraSource.Demo,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private val back = CameraProfile(
        id = CameraId("cam-belakang"),
        label = CameraLabel("Warung Belakang"),
        source = CameraSource.Rtsp("192.168.1.64", 554, "/h264/ch1/sub/av_stream", "viewer"),
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun state(
        cameras: List<CameraProfile> = listOf(front),
        editing: CameraEditState? = null,
        pendingDelete: CameraProfile? = null,
        isLoading: Boolean = false,
    ) = CameraListState(
        cameras = cameras,
        editing = editing,
        pendingDelete = pendingDelete,
        message = null,
        isLoading = isLoading,
    )

    private fun render(
        state: CameraListState,
        onAdd: () -> Unit = {},
        onOpen: (CameraId) -> Unit = {},
        onEdit: (CameraId) -> Unit = {},
        onDelete: (CameraId) -> Unit = {},
        onConfirmDelete: () -> Unit = {},
        onEditorSave: () -> Unit = {},
        onEditorLabelChange: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            CameraListScreen(
                state = state,
                onAdd = onAdd,
                onEdit = onEdit,
                onDelete = onDelete,
                onOpen = onOpen,
                onDismissEditor = {},
                onEditorLabelChange = onEditorLabelChange,
                onEditorSourceChange = {},
                onEditorHostChange = {},
                onEditorPortChange = {},
                onEditorPathChange = {},
                onEditorUsernameChange = {},
                onEditorSave = onEditorSave,
                onDismissDelete = {},
                onConfirmDelete = onConfirmDelete,
                onDismissMessage = {},
            )
        }
    }

    @Test
    fun empty_list_shows_the_add_call_to_action() {
        var adds = 0
        render(state(cameras = emptyList()), onAdd = { adds++ })

        composeRule.onNodeWithText("Belum ada kamera").assertExists()
        composeRule.onNodeWithTag("empty-add-camera").performClick()

        composeRule.runOnIdle { assertEquals(1, adds) }
    }

    @Test
    fun camera_card_opens_the_selected_camera() {
        var opened: CameraId? = null
        render(state(cameras = listOf(front)), onOpen = { opened = it })

        composeRule.onNodeWithTag("open-camera-cam-depan").performClick()

        composeRule.runOnIdle { assertEquals(front.id, opened) }
    }

    @Test
    fun rtsp_card_shows_endpoint_without_credentials() {
        render(state(cameras = listOf(back)))

        composeRule.onNodeWithText("RTSP - 192.168.1.64:554").assertExists()
        composeRule.onNodeWithText("viewer", substring = true).assertDoesNotExist()
    }

    @Test
    fun delete_requires_the_confirmation_dialog() {
        var confirmed = 0
        render(
            state(cameras = listOf(front), pendingDelete = front),
            onConfirmDelete = { confirmed++ },
        )

        composeRule.onNodeWithText("Hapus Warung Depan?").assertExists()
        composeRule.onNodeWithTag("confirm-delete").performClick()

        composeRule.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test
    fun editor_prefills_the_existing_camera() {
        render(
            state(cameras = listOf(back), editing = CameraEditState.from(back)),
        )

        composeRule.onNodeWithText("Ubah kamera").assertExists()
        composeRule.onNodeWithTag("camera-label").assertIsDisplayed()
        composeRule.onNodeWithText("192.168.1.64").assertExists()
    }

    @Test
    fun editor_save_button_calls_back() {
        var saves = 0
        render(
            state(cameras = listOf(front), editing = CameraEditState.from(front)),
            onEditorSave = { saves++ },
        )

        composeRule.onNodeWithTag("save-camera-bottom").performClick()

        composeRule.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-mdpi")
    fun wide_layout_shows_both_cards() {
        render(state(cameras = listOf(front, back)))

        composeRule.onNodeWithTag("camera-cam-depan").assertExists()
        composeRule.onNodeWithTag("camera-cam-belakang").assertExists()
    }
}