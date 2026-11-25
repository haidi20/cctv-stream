package id.warung.monitor.feature.tables.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import id.warung.monitor.feature.tables.data.InMemoryTableConfigRepository
import id.warung.monitor.feature.tables.engine.TableApplyResult
import id.warung.monitor.feature.tables.engine.TableConfigSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A frozen frame belongs to one camera only. Rebinding must drop it so a map
 * for camera B is never drawn on a picture from camera A.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableMappingSnapshotTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): TableMappingViewModel = TableMappingViewModel(
        InMemoryTableConfigRepository(),
        TableConfigSink { _, _ -> TableApplyResult.Applied },
    ).also { it.bindCamera("cam-depan", "Warung Depan") }

    private fun frame(): ImageBitmap = object : ImageBitmap {
        override val width: Int = 8
        override val height: Int = 4
        override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val hasAlpha: Boolean = false

        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) = Unit

        override fun prepareToDraw() = Unit
    }

    @Test
    fun set_snapshot_keeps_the_frozen_frame() = runTest(dispatcher) {
        val model = viewModel()
        model.setSnapshot(frame())
        assertNotNull(model.state.value.snapshot)
        model.clearSnapshot()
        assertNull(model.state.value.snapshot)
    }

    @Test
    fun binding_another_camera_drops_the_frozen_frame() = runTest(dispatcher) {
        val model = viewModel()
        model.setSnapshot(frame())
        model.bindCamera("cam-belakang", "Warung Belakang")
        assertNull(model.state.value.snapshot)
    }
}