package id.warung.monitor.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import id.warung.monitor.core.sync.SyncCoordinator
import id.warung.monitor.core.sync.SyncSettings
import id.warung.monitor.core.sync.SyncTransport
import id.warung.monitor.core.sync.HeartbeatTransport
import id.warung.monitor.core.sync.LocalHistoryStore
import id.warung.monitor.core.sync.RetentionSettings
import id.warung.monitor.core.database.MonitorDatabase
import id.warung.monitor.data.DatabaseSize
import id.warung.monitor.data.OutboxRepository
import id.warung.monitor.data.RoomTableHistory
import id.warung.monitor.data.TableHistory
import id.warung.monitor.data.TableHistoryPruner
import id.warung.monitor.data.DeviceIdStore
import id.warung.monitor.feature.home.domain.EngineStatusSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideSyncDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.syncDataStore

    @Provides
    @Singleton
    fun provideSyncTokenStore(store: KeystoreSyncTokenStore): SyncTokenStore = store

    @Provides
    @Singleton
    fun provideSyncSettings(
        store: DataStore<Preferences>,
        tokens: SyncTokenStore,
    ): SyncSettings = DataStoreSyncSettings(store, tokens)

    @Provides
    @Singleton
    fun provideSyncAttemptStore(store: DataStore<Preferences>): SyncAttemptStore =
        DataStoreSyncAttemptStore(store)

    /** Retention lives next to the endpoint; both are plain preferences. */
    @Provides
    @Singleton
    fun provideRetentionSettings(store: DataStore<Preferences>): RetentionSettings =
        DataStoreRetentionSettings(store)

    /** The database file, not a directory walk: one file, one number. */
    @Provides
    @Singleton
    fun provideDatabaseSize(@ApplicationContext context: Context): DatabaseSize =
        DatabaseSize { context.getDatabasePath(MonitorDatabase.NAME).length() }

    @Provides
    @Singleton
    fun provideTableHistory(history: RoomTableHistory): TableHistory = history

    @Provides
    @Singleton
    fun provideTableHistoryPruner(history: RoomTableHistory): TableHistoryPruner = history

    @Provides
    @Singleton
    fun provideLocalHistoryStore(history: RoomTableHistory): LocalHistoryStore = history

    @Provides
    @Singleton
    fun provideHistoryPruner(
        settings: RetentionSettings,
        history: TableHistoryPruner,
    ): HistoryPruner = HistoryPruner(settings = settings, history = history)

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            // The payload is tiny; a long timeout only hides a dead VPS.
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    @Provides
    @Singleton
    fun provideSyncTransport(client: HttpClient, settings: SyncSettings): SyncTransport =
        KtorSyncTransport(client) { settings.endpoint() }

    @Provides
    @Singleton
    fun provideSyncCoordinator(
        outbox: OutboxRepository,
        transport: SyncTransport,
        history: TableHistory,
    ): SyncCoordinator = SyncCoordinator(
        outbox = OutboxSyncAdapter(outbox, history),
        transport = transport,
    )

    @Provides
    @Singleton
    fun provideHeartbeatTransport(
        client: HttpClient,
        settings: SyncSettings,
    ): HeartbeatTransport = KtorHeartbeatTransport(client) { settings.endpoint() }

    @Provides
    @Singleton
    fun provideHeartbeatRunner(
        statusSource: EngineStatusSource,
        facts: AndroidHeartbeatFacts,
        transport: HeartbeatTransport,
        settings: SyncSettings,
        outbox: OutboxRepository,
    ): HeartbeatRunner = HeartbeatRunner(
        statusSource = statusSource,
        facts = facts::read,
        transport = transport,
        settings = settings,
        outbox = outbox,
    )

    @Provides
    @Singleton
    fun provideSyncRunner(
        coordinator: SyncCoordinator,
        outbox: OutboxRepository,
        deviceIds: DeviceIdStore,
        attempts: SyncAttemptStore,
    ): SyncRunner = SyncRunner(
        coordinator = coordinator,
        outbox = outbox,
        deviceId = deviceIds::deviceId,
        attempts = attempts,
    )
}

