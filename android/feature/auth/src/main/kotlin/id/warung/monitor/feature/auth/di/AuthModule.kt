package id.warung.monitor.feature.auth.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import id.warung.monitor.feature.auth.data.AuthStore
import id.warung.monitor.feature.auth.data.BootClock
import id.warung.monitor.feature.auth.data.DataStoreAuthStore
import id.warung.monitor.feature.auth.data.authDataStore
import id.warung.monitor.feature.auth.domain.LocalDataEraser
import id.warung.monitor.feature.auth.domain.LockoutPolicy
import id.warung.monitor.feature.auth.domain.MonotonicClock
import id.warung.monitor.feature.auth.domain.Pbkdf2PinHasher
import id.warung.monitor.feature.auth.domain.PinHasher
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun providePinHasher(): PinHasher = Pbkdf2PinHasher()

    @Provides
    @Singleton
    fun provideLockoutPolicy(): LockoutPolicy = LockoutPolicy()

    @Provides
    @Singleton
    fun provideAuthStore(@ApplicationContext context: Context): AuthStore =
        DataStoreAuthStore(context.authDataStore) { BootClock.token(context) }

    @Provides
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock {
        android.os.SystemClock.elapsedRealtime()
    }

    /** PBKDF2 is CPU-bound, so it never runs on the main thread. */
    @Provides
    fun provideAuthDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideLocalDataEraser(eraser: AppDataEraser): LocalDataEraser = eraser
}