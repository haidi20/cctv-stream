package id.warung.monitor

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import id.warung.monitor.data.TableEventRecorder
import id.warung.monitor.sync.SyncScheduler
import javax.inject.Inject

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
    }
}
