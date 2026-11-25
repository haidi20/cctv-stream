package id.warung.monitor

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.engine.EngineFramePump
import id.warung.monitor.engine.Media3CameraPreview
import id.warung.monitor.feature.auth.ui.LoginRoute
import id.warung.monitor.feature.cameras.data.CameraRepository
import id.warung.monitor.feature.cameras.session.CameraSession
import id.warung.monitor.feature.cameras.session.CameraSessionResult
import id.warung.monitor.feature.cameras.ui.CameraListRoute
import id.warung.monitor.feature.home.ui.HomeRoute
import id.warung.monitor.feature.tables.ui.TableMappingRoute
import id.warung.monitor.session.AuthSession
import id.warung.monitor.ui.CameraPreviewSurface
import id.warung.monitor.ui.MappingCameraPreview
import id.warung.monitor.ui.theme.MonitorTheme
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-activity shell: login -> home -> scan sheet -> live totals.
 *
 * Camera management and table mapping are secondary destinations reached from
 * the Home overflow menu, so the primary flow never needs a navigation bar.
 * The session is memory-only: process death always returns to the lock screen.
 * `FLAG_SECURE` is set before content is drawn, so PIN and stream never appear
 * in the task switcher.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var session: CameraSession

    @Inject
    lateinit var preview: Media3CameraPreview

    @Inject
    lateinit var framePump: EngineFramePump

    @Inject
    lateinit var cameras: CameraRepository

    @Inject
    lateinit var auth: AuthSession

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        setContent {
            MonitorTheme {
                AppShell(
                    session = session,
                    preview = preview,
                    framePump = framePump,
                    cameras = cameras,
                    auth = auth,
                )
            }
        }
    }
}
private val DestinationSaver: Saver<ShellDestination, String> = Saver(
    save = { destination ->
        when (destination) {
            ShellDestination.Home -> "home"
            ShellDestination.Cameras -> "cameras"
            is ShellDestination.Mapping -> "mapping|${destination.cameraId}|${destination.label}"
        }
    },
    restore = { value ->
        when {
            value == "cameras" -> ShellDestination.Cameras
            value.startsWith("mapping|") -> {
                val parts = value.split("|", limit = 3)
                if (parts.size == 3) ShellDestination.Mapping(parts[1], parts[2]) else ShellDestination.Home
            }
            else -> ShellDestination.Home
        }
    },
)

private sealed interface ShellDestination {
    data object Home : ShellDestination
    data object Cameras : ShellDestination
    data class Mapping(val cameraId: String, val label: String) : ShellDestination
}

@Composable
private fun AppShell(
    session: CameraSession,
    preview: Media3CameraPreview,
    framePump: EngineFramePump,
    cameras: CameraRepository,
    auth: AuthSession,
    modifier: Modifier = Modifier,
) {
    val unlocked by auth.unlocked.collectAsStateWithLifecycle()
    var destination by rememberSaveable(stateSaver = DestinationSaver) {
        mutableStateOf<ShellDestination>(ShellDestination.Home)
    }
    var opening by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleMutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, auth) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> auth.onBackground(SystemClock.elapsedRealtime())
                Lifecycle.Event.ON_START -> auth.onForeground(SystemClock.elapsedRealtime())
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    suspend fun closeSession() {
        lifecycleMutex.withLock {
            if (opening) return@withLock
            preview.close()
            session.close()
        }
    }

    /**
     * Opens one camera at a time. The Rust session must accept the camera before
     * the mapping screen is shown, otherwise `setTables` would be rejected as
     * `WrongCamera` and the user would lose their map.
     */
    suspend fun openMapping(profile: CameraProfile) {
        lifecycleMutex.withLock {
            if (opening) return@withLock
            opening = true
            preview.close()
            val result = try {
                session.open(profile.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                CameraSessionResult.Failed("Sesi kamera gagal dibuka")
            }
            when (result) {
                CameraSessionResult.Opened -> {
                    destination = ShellDestination.Mapping(profile.id.value, profile.label.value)
                }
                is CameraSessionResult.Failed -> message = result.reason
            }
            opening = false
        }
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        message = null
    }

    BackHandler(enabled = unlocked && destination != ShellDestination.Home) {
        when (destination) {
            is ShellDestination.Mapping -> {
                destination = ShellDestination.Cameras
                scope.launch { closeSession() }
            }
            ShellDestination.Cameras -> destination = ShellDestination.Home
            ShellDestination.Home -> Unit
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!unlocked) {
            // Logout and process death both land here. No navigation bar.
            LaunchedEffect(Unit) {
                destination = ShellDestination.Home
                closeSession()
            }
            LoginRoute(onUnlocked = auth::unlock)
        } else {
            when (val current = destination) {
                ShellDestination.Home -> HomeRoute(
                    onManageCameras = { destination = ShellDestination.Cameras },
                    onLogout = {
                        destination = ShellDestination.Home
                        auth.lock()
                        scope.launch { closeSession() }
                    },
                    preview = { profile: CameraProfile ->
                        CameraPreviewSurface(preview = preview, camera = profile)
                    },
                )
                ShellDestination.Cameras -> CameraListRoute(
                    onOpenCamera = { profile -> scope.launch { openMapping(profile) } },
                )
                is ShellDestination.Mapping -> {
                    if (opening) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        TableMappingRoute(
                            cameraId = current.cameraId,
                            cameraLabel = current.label,
                            onBack = {
                                destination = ShellDestination.Cameras
                                scope.launch { closeSession() }
                            },
                            preview = { onVideoSize, onSnapshot ->
                                var profile by remember(current.cameraId) {
                                    mutableStateOf<CameraProfile?>(null)
                                }
                                LaunchedEffect(current.cameraId) {
                                    profile = runCatching {
                                        cameras.find(CameraId(current.cameraId))
                                    }.getOrNull()
                                }
                                val resolved = profile
                                if (resolved == null) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                } else {
                                    MappingCameraPreview(
                                        preview = preview,
                                        camera = resolved,
                                        framePump = framePump,
                                        onVideoSize = onVideoSize,
                                        onSnapshot = onSnapshot,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}