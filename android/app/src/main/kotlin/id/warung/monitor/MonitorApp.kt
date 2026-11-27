package id.warung.monitor

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import id.warung.monitor.data.TableEventRecorder
import id.warung.monitor.engine.DevSeed
import id.warung.monitor.sync.SyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hilt root. Kept deliberately small: no network init, login stays offline.
 *
 * The recorder is started here rather than in a screen so a table transition is
 * queued even when the user is not looking at Home. It only subscribes to the
 * engine; it never opens a stream or touches the network.
 */
@HiltAndroidApp
class MonitorApp : Application(), Configuration.Provider {

    @Inject
    lateinit var tableEventRecorder: TableEventRecorder

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var devSeed: DevSeed

    /**
     * Start-up work that must not block [onCreate]. A failure here is logged by
     * the task itself; it never stops the app from starting.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * WorkManager is initialised on demand, after Hilt has built the worker
     * factory. Without this, the default initializer runs before injection and
     * `SyncWorker` cannot be constructed.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        tableEventRecorder.start()
        // Sync is opt-in: with no endpoint the worker exits immediately, and a
        // start-up trigger also flushes rows queued while the app was closed.
        runCatching { SyncScheduler.enqueue(this) }
        // Debug builds only: seed one local-video camera so the preview and the
        // Rust analysis path can be exercised without a network. Release builds
        // return immediately, so a shop device never sees test footage.
        appScope.launch { runCatching { devSeed.ensureSeeded() } }
    }
}
