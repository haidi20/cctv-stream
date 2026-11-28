package id.warung.monitor

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.engine.EngineFramePump
import id.warung.monitor.feature.home.domain.CameraSecretReader
import id.warung.monitor.engine.Media3CameraPreview
import id.warung.monitor.feature.auth.ui.LoginRoute
import id.warung.monitor.feature.cameras.data.CameraRepository
import id.warung.monitor.feature.cameras.session.CameraSession
import id.warung.monitor.feature.cameras.session.CameraSessionResult
import id.warung.monitor.feature.cameras.ui.CameraListRoute
import id.warung.monitor.feature.home.domain.HomePowerMode
import id.warung.monitor.feature.home.ui.HomeRoute
import id.warung.monitor.feature.onboarding.domain.DeviceProfile
import id.warung.monitor.feature.onboarding.ui.DeviceCheckRoute
import id.warung.monitor.feature.settings.ui.RetentionRoute
import id.warung.monitor.feature.settings.ui.SyncSettingsRoute
import id.warung.monitor.feature.tables.ui.TableMappingRoute
import id.warung.monitor.service.MonitoringHub
import id.warung.monitor.service.NotificationPermission
import id.warung.monitor.sync.HistoryPruneScheduler
import id.warung.monitor.sync.SyncScheduler
import id.warung.monitor.feature.auth.domain.BiometricUnlocker
import id.warung.monitor.session.AndroidBiometricUnlock
import id.warung.monitor.session.AuthSession
import id.warung.monitor.ui.CameraPreviewSurface
import id.warung.monitor.ui.MappingCameraPreview
import id.warung.monitor.core.ui.theme.MonitorTheme
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

    @Inject
    lateinit var monitoring: MonitoringHub

    @Inject
    lateinit var secrets: CameraSecretReader

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEV_MODE) auth.enableDevMode()
        setContent {
            MonitorTheme {
                AppShell(
                    session = session,
                    preview = preview,
                    framePump = framePump,
                    cameras = cameras,
                    auth = auth,
                    monitoring = monitoring,
                    secrets = secrets,
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
            ShellDestination.Settings -> "settings"
            ShellDestination.Retention -> "retention"
            is ShellDestination.Mapping -> "mapping|${destination.cameraId}|${destination.label}"
        }
    },
    restore = { value ->
        when {
            value == "cameras" -> ShellDestination.Cameras
            value == "settings" -> ShellDestination.Settings
            value == "retention" -> ShellDestination.Retention
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
    data object Settings : ShellDestination
    data object Retention : ShellDestination
    data class Mapping(val cameraId: String, val label: String) : ShellDestination
}

@Composable
private fun AppShell(
    session: CameraSession,
    preview: Media3CameraPreview,
    framePump: EngineFramePump,
    cameras: CameraRepository,
    auth: AuthSession,
    monitoring: MonitoringHub,
    secrets: CameraSecretReader,
    modifier: Modifier = Modifier,
) {
    var onboardingProfile by rememberSaveable { mutableStateOf<String?>(null) }
    val profile = onboardingProfile?.let { stored ->
        DeviceProfile.entries.firstOrNull { it.name == stored }
    }
    if (profile == null) {
        val activity = LocalActivity.current
        DeviceCheckRoute(
            onApproved = { approved -> onboardingProfile = approved.name },
            onCloseApp = { activity?.finish() },
            modifier = modifier,
        )
        return
    }
    val unlocked by auth.unlocked.collectAsStateWithLifecycle()
    var destination by rememberSaveable(stateSaver = DestinationSaver) {
        mutableStateOf<ShellDestination>(ShellDestination.Home)
    }
    var opening by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleMutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    // Monitoring continues with the screen off, so the notification permission
    // is requested when a stream first starts, never on the login screen.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val monitoringActive by monitoring.active.collectAsStateWithLifecycle()
    LaunchedEffect(monitoringActive) {
        if (!monitoringActive) return@LaunchedEffect
        val permission = NotificationPermission.permissionToRequest(context)
        if (permission != null) notificationPermission.launch(permission)
    }


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
            // Covers logout and back-navigation even when Home's state is no
            // longer collected: the notification must never outlive the stream.
            monitoring.stop()
        }
    }
    // "Hentikan" from the notification must also close the camera, otherwise
    // the RTSP session would keep running with no visible way to stop it.
    LaunchedEffect(monitoring) {
        monitoring.stopRequests.collect { closeSession() }
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
            ShellDestination.Settings -> destination = ShellDestination.Home
            ShellDestination.Retention -> destination = ShellDestination.Settings
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
            val hostActivity = LocalActivity.current
            val biometricUnlocker = remember(hostActivity) {
                hostActivity?.let { BiometricUnlocker(AndroidBiometricUnlock(it)) }
            }
            LoginRoute(onUnlocked = auth::unlock, biometricUnlocker = biometricUnlocker)
        } else {
            when (val current = destination) {
                ShellDestination.Home -> HomeRoute(
                    powerMode = if (profile == DeviceProfile.Lite) {
                        HomePowerMode.Saver
                    } else {
                        HomePowerMode.Standard
                    },
                    appVersion = BuildConfig.VERSION_NAME,
                    devMode = BuildConfig.DEV_MODE,
                    onManageCameras = { destination = ShellDestination.Cameras },
                    onOpenSettings = { destination = ShellDestination.Settings },
                    onLogout = {
                        destination = ShellDestination.Home
                        auth.lock()
                        scope.launch { closeSession() }
                    },
                    preview = { profile: CameraProfile ->
                        LaunchedEffect(profile.id) {
                            val secret = try {
                                secrets.read(profile.id)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                null
                            }
                            if (secret != null) preview.open(profile, secret)
                        }
                        CameraPreviewSurface(preview = preview, camera = profile, framePump = framePump)
                    },
                )
                ShellDestination.Cameras -> CameraListRoute(
                    onOpenCamera = { profile -> scope.launch { openMapping(profile) } },
                    onMonitoringChange = { id, enabled ->
                        scope.launch { cameras.setMonitoringEnabled(id, enabled) }
                    },
                    onReconnect = { id ->
                        scope.launch {
                            closeSession()
                            cameras.find(id)?.let { profile -> openMapping(profile) }
                        }
                    },
                )
                ShellDestination.Settings -> SyncSettingsRoute(
                    onBack = { destination = ShellDestination.Home },
                    onSaved = { SyncScheduler.restart(context) },
                    onOpenRetention = { destination = ShellDestination.Retention },
                )
                ShellDestination.Retention -> RetentionRoute(
                    onBack = { destination = ShellDestination.Settings },
                    // The window is read at prune time, so this only refreshes
                    // the schedule; nothing is deleted while the user watches.
                    onSaved = { HistoryPruneScheduler.reschedule(context) },
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
                                    LaunchedEffect(resolved.id) {
                                        val secret = try {
                                            secrets.read(resolved.id)
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (_: Throwable) {
                                            null
                                        }
                                        if (secret != null) preview.open(resolved, secret)
                                    }
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
