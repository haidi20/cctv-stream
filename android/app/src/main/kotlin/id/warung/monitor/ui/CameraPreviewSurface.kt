package id.warung.monitor.ui

import android.view.LayoutInflater
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import id.warung.monitor.R
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.engine.EngineFramePump
import id.warung.monitor.engine.Media3CameraPreview
import id.warung.monitor.engine.VideoCaptures
import id.warung.monitor.feature.home.domain.PreviewState
import kotlinx.coroutines.launch

/**
 * Preview slot supplied to Home and mapping.
 *
 * Demo cameras get a drawn room instead of a video file, so no stock footage
 * ships in the APK. RTSP cameras and a dev-mode local clip get the same muted,
 * controls-less [PlayerView] bound to the application-scoped player. The player
 * is collected as state now: the previous `remember { playerOrNull() }` could
 * capture `null` before `open()` completed and stay black forever.
 *
 * The layout requests `surface_type="texture_view"` so in-process captures work
 * even though the activity uses `FLAG_SECURE`. When [framePump] is supplied, a
 * 2 FPS capture feeds the Rust engine so the model sees the same frames.
 */
@OptIn(UnstableApi::class)
@Composable
fun CameraPreviewSurface(
    preview: Media3CameraPreview,
    camera: CameraProfile,
    modifier: Modifier = Modifier,
    framePump: EngineFramePump? = null,
    onVideoSize: (Int, Int) -> Unit = { _, _ -> },
    onTextureReady: (TextureView?) -> Unit = {},
) {
    when (camera.source) {
        CameraSource.Demo -> {
            LaunchedEffect(camera.id) { onTextureReady(null) }
            DemoRoom(modifier)
        }
        // A dev-mode clip and RTSP both play through the same player surface, so
        // the frame pump captures either source unchanged.
        is CameraSource.Video, is CameraSource.Rtsp -> PlayerSurface(
            preview = preview,
            camera = camera,
            modifier = modifier,
            framePump = framePump,
            onVideoSize = onVideoSize,
            onTextureReady = onTextureReady,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    preview: Media3CameraPreview,
    camera: CameraProfile,
    modifier: Modifier,
    framePump: EngineFramePump?,
    onVideoSize: (Int, Int) -> Unit,
    onTextureReady: (TextureView?) -> Unit,
) {
    val player by preview.player.collectAsStateWithLifecycle()
    val current = player
    var texture by remember { mutableStateOf<TextureView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(current, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onVideoSize(videoSize.width, videoSize.height)
                }
            }
        }
        current?.addListener(listener)
        onDispose { current?.removeListener(listener) }
    }

    if (current == null) {
        LaunchedEffect(Unit) { onTextureReady(null) }
        Box(modifier = modifier.fillMaxSize().background(Color.Black))
        return
    }

    DisposableEffect(current) {
        onDispose { current.clearVideoSurface() }
    }

    AndroidView(
        factory = { context ->
            val view = LayoutInflater.from(context)
                .inflate(R.layout.preview_player, FrameLayout(context), false) as PlayerView
            view.setShutterBackgroundColor(android.graphics.Color.BLACK)
            view.player = current
            texture = view.videoSurfaceView as? TextureView
            onTextureReady(texture)
            view
        },
        modifier = modifier.fillMaxSize(),
        update = { view -> view.player = current },
        onRelease = { view ->
            view.player = null
            texture = null
            onTextureReady(null)
        },
    )

    val previewState by preview.state.collectAsStateWithLifecycle()
    if (framePump != null && previewState is PreviewState.Playing) {
        val previewStateFlow = preview.state
        LaunchedEffect(current, camera.id) {
            framePump.run(
                cameraId = camera.id.value,
                textureProvider = { texture },
                // A frozen texture would keep feeding Rust the same picture,
                // hiding a dead camera from its watchdog. The flow value is
                // read per tick, and the effect is keyed on Playing so a pump
                // never starts before the player has a live texture.
                keepRunning = { previewStateFlow.value is PreviewState.Playing },
            )
        }
    }
}

/**
 * Live stream plus a "freeze frame" action used by the mapping editor.
 *
 * Freezing gives the canvas a stable bitmap, so pinch/pan and the polygon share
 * the same transform. Capturing is explicit: the user decides which frame to map.
 */
@OptIn(UnstableApi::class)
@Composable
fun MappingCameraPreview(
    preview: Media3CameraPreview,
    camera: CameraProfile,
    modifier: Modifier = Modifier,
    framePump: EngineFramePump? = null,
    onVideoSize: (Int, Int) -> Unit = { _, _ -> },
    onSnapshot: (ImageBitmap) -> Unit,
) {
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        CameraPreviewSurface(
            preview = preview,
            camera = camera,
            framePump = framePump,
            onVideoSize = onVideoSize,
            onTextureReady = { texture = it },
        )
        Button(
            onClick = {
                val target = texture ?: return@Button
                if (capturing) return@Button
                capturing = true
                failure = false
                scope.launch {
                    val bitmap = VideoCaptures.capture(target)
                    if (bitmap == null) {
                        failure = true
                    } else {
                        onSnapshot(bitmap.asImageBitmap())
                    }
                    capturing = false
                }
            },
            enabled = texture != null && !capturing,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .testTag("freeze-frame"),
        ) {
            Text(if (capturing) "Membekukan..." else "Bekukan gambar")
        }
        if (failure) {
            Text(
                text = "Gagal membekukan gambar",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp),
            )
        }
    }
}

@Composable
private fun DemoRoom(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize().background(Color(0xFF1B1D20))) {
        drawDemoRoom()
    }
}

private fun DrawScope.drawDemoRoom() {
    val w = size.width
    val h = size.height
    drawRect(Color(0xFF23262A), size = Size(w, h))
    drawRect(Color(0xFF16181B), size = Size(w, h * 0.18f))

    val grid = Color.White.copy(alpha = 0.05f)
    for (step in 1 until 10) {
        val x = w * step / 10f
        val y = h * step / 10f
        drawLine(grid, Offset(x, h * 0.18f), Offset(x, h), strokeWidth = 1f)
        drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }

    listOf(
        0.08f to 0.52f,
        0.36f to 0.24f,
        0.68f to 0.50f,
        0.42f to 0.72f,
    ).forEach { (x, y) ->
        val topLeft = Offset(x * w, y * h)
        val tableSize = Size(0.18f * w, 0.18f * h)
        drawRoundRect(
            color = Color(0xFF8A6A4A),
            topLeft = topLeft,
            size = tableSize,
            cornerRadius = CornerRadius(12f),
        )
        drawCircle(
            color = Color(0xFFB08A62),
            radius = 5f,
            center = Offset(topLeft.x + tableSize.width / 2, topLeft.y + tableSize.height / 2),
        )
    }
}