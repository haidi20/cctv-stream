package id.warung.monitor.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import id.warung.monitor.core.database.CameraProfileDao
import id.warung.monitor.core.database.MonitorDatabase
import id.warung.monitor.core.database.OutboxDao
import id.warung.monitor.core.database.TableConfigRevisionDao
import id.warung.monitor.core.database.TableLogDao
import id.warung.monitor.core.model.CameraId
import id.warung.monitor.core.network.LocalNetwork
import id.warung.monitor.core.network.WsDiscoverySource
import id.warung.monitor.data.AndroidConnectionTestSource
import id.warung.monitor.data.AndroidLocalNetwork
import id.warung.monitor.data.AndroidNetworkMonitor
import id.warung.monitor.data.DataStoreMonitoringPreference
import id.warung.monitor.data.CompositeEngineStatusSource
import id.warung.monitor.data.NetworkCameraScanSource
import id.warung.monitor.data.UdpWsDiscoverySource
import id.warung.monitor.feature.cameras.ui.CameraScanSource
import id.warung.monitor.feature.cameras.ui.ConnectionTestSource
import id.warung.monitor.data.DemoEngineStatusSource
import id.warung.monitor.data.KeystoreCameraSecretStore
import id.warung.monitor.data.RoomDataEraser
import id.warung.monitor.data.RoomTableMappingSource
import id.warung.monitor.data.RustEngineEventSource
import id.warung.monitor.data.RustEngineStatusSource
import id.warung.monitor.data.RustStreamFailureReporter
import id.warung.monitor.engine.AndroidDeviceLoadSource
import id.warung.monitor.engine.DeviceLoadSource
import id.warung.monitor.engine.EngineStartPlanner
import id.warung.monitor.engine.Media3CameraPreview
import id.warung.monitor.engine.RustCameraSession
import id.warung.monitor.engine.RustTableConfigSink
import id.warung.monitor.feature.auth.di.AppDataEraser
import id.warung.monitor.feature.cameras.data.CameraRepository
import id.warung.monitor.feature.cameras.data.CameraSecretStore
import id.warung.monitor.feature.cameras.data.RoomCameraRepository
import id.warung.monitor.feature.cameras.session.CameraSession
import id.warung.monitor.feature.cameras.ui.CameraIdFactory
import id.warung.monitor.feature.home.domain.CameraPreviewCoordinator
import id.warung.monitor.feature.home.domain.CameraSecretReader
import id.warung.monitor.feature.home.domain.EngineAnalysisSource
import id.warung.monitor.feature.home.domain.EngineChangeSignal
import id.warung.monitor.feature.home.domain.EngineStatusSource
import id.warung.monitor.feature.home.domain.HomeClock
import id.warung.monitor.feature.home.domain.MonitoringPreference
import id.warung.monitor.feature.home.domain.MonitoringSignal
import id.warung.monitor.feature.home.domain.HomePollInterval
import id.warung.monitor.feature.home.domain.NetworkMonitor
import id.warung.monitor.feature.home.domain.StreamFailureReporter
import id.warung.monitor.feature.home.domain.TableMappingSource
import id.warung.monitor.feature.tables.data.RoomTableConfigRepository
import id.warung.monitor.service.MonitoringHub
import id.warung.monitor.feature.tables.data.TableConfigRepository
import id.warung.monitor.feature.tables.engine.TableConfigSink
import java.util.UUID
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MonitorDatabase =
        MonitorDatabase.create(context)

    @Provides
    fun provideTableConfigRevisionDao(database: MonitorDatabase): TableConfigRevisionDao =
        database.tableConfigRevisions()

    @Provides
    fun provideCameraProfileDao(database: MonitorDatabase): CameraProfileDao =
        database.cameraProfiles()

    @Provides
    fun provideOutboxDao(database: MonitorDatabase): OutboxDao = database.outbox()

    @Provides
    fun provideTableLogDao(database: MonitorDatabase): TableLogDao = database.tableLogs()

    @Provides
    @Singleton
    fun provideTableConfigRepository(dao: TableConfigRevisionDao): TableConfigRepository =
        RoomTableConfigRepository(dao)

    @Provides
    @Singleton
    fun provideCameraRepository(dao: CameraProfileDao): CameraRepository =
        RoomCameraRepository.from(dao)

    @Provides
    @Singleton
    fun provideCameraSecretStore(store: KeystoreCameraSecretStore): CameraSecretStore = store

    @Provides
    fun provideCameraSecretReader(store: CameraSecretStore): CameraSecretReader =
        CameraSecretReader { id -> store.read(id) }

    /** Ids only ever contain the alphabet both Room and `core-ffi` accept. */
    @Provides
    fun provideEngineStartPlanner(): EngineStartPlanner = EngineStartPlanner()

    @Provides
    fun provideCameraIdFactory(): CameraIdFactory = CameraIdFactory {
        CameraId("cam-" + UUID.randomUUID().toString().replace("-", "").take(20))
    }

    @Provides
    @Singleton
    fun provideCameraSession(session: RustCameraSession): CameraSession = session

    @Provides
    @Singleton
    fun provideTableConfigSink(sink: RustTableConfigSink): TableConfigSink = sink

    @Provides
    @Singleton
    fun provideEngineStatusSource(
        demo: DemoEngineStatusSource,
        rust: RustEngineStatusSource,
    ): EngineStatusSource = CompositeEngineStatusSource(demo, rust)

    @Provides
    @Singleton
    fun provideEngineChangeSignal(source: RustEngineEventSource): EngineChangeSignal = source

    /**
     * Rust is the only owner of per-table status and person boxes. The status
     * source already reads the same engine, so it serves both interfaces
     * instead of opening a second FFI path.
     */
    @Provides
    @Singleton
    fun provideEngineAnalysisSource(source: RustEngineStatusSource): EngineAnalysisSource = source

    @Provides
    @Singleton
    fun provideCameraPreviewCoordinator(preview: Media3CameraPreview): CameraPreviewCoordinator =
        preview

    @Provides
    @Singleton
    fun provideMonitoringSignal(hub: MonitoringHub): MonitoringSignal = hub

    @Provides
    @Singleton
    fun provideMonitoringPreference(
        preference: DataStoreMonitoringPreference,
    ): MonitoringPreference = preference

    @Provides
    @Singleton
    fun provideStreamFailureReporter(reporter: RustStreamFailureReporter): StreamFailureReporter =
        reporter

    /**
     * Room is the source of truth for camera table maps, so Home learns whether
     * analysis has anything to compare a person box against without asking the
     * engine (a stopped engine must not answer "belum ada peta").
     */
    @Provides
    @Singleton
    fun provideTableMappingSource(source: RoomTableMappingSource): TableMappingSource = source

    @Provides
    fun provideHomeClock(): HomeClock = HomeClock { System.currentTimeMillis() }

    @Provides
    fun provideHomePollInterval(): HomePollInterval =
        HomePollInterval(HomePollInterval.DEFAULT_MILLIS)

    @Provides
    @Singleton
    fun provideAppDataEraser(eraser: RoomDataEraser): AppDataEraser = eraser

    @Provides
    @Singleton
    fun provideLocalNetwork(impl: AndroidLocalNetwork): LocalNetwork = impl

    @Provides
    @Singleton
    fun provideWsDiscovery(impl: UdpWsDiscoverySource): WsDiscoverySource = impl

    @Provides
    @Singleton
    fun provideCameraScanSource(source: NetworkCameraScanSource): CameraScanSource = source

    @Provides
    @Singleton
    fun provideConnectionTestSource(source: AndroidConnectionTestSource): ConnectionTestSource = source

    @Provides
    @Singleton
    fun provideNetworkMonitor(monitor: AndroidNetworkMonitor): NetworkMonitor = monitor

    @Provides
    @Singleton
    fun provideDeviceLoadSource(source: AndroidDeviceLoadSource): DeviceLoadSource = source
}
