package id.warung.monitor.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.warung.monitor.data.AndroidDeviceCapabilitySource
import id.warung.monitor.engine.RustEngineWarmUp
import id.warung.monitor.feature.onboarding.domain.DeviceCapabilitySource
import id.warung.monitor.feature.onboarding.domain.EngineWarmUp
import id.warung.monitor.feature.onboarding.domain.OnboardingClock
import javax.inject.Singleton

/** Binds the onboarding contracts to their Android/native implementations. */
@Module
@InstallIn(SingletonComponent::class)
object OnboardingBindings {

    @Provides
    @Singleton
    fun provideDeviceCapabilitySource(
        source: AndroidDeviceCapabilitySource,
    ): DeviceCapabilitySource = source

    @Provides
    @Singleton
    fun provideEngineWarmUp(warmUp: RustEngineWarmUp): EngineWarmUp = warmUp

    @Provides
    fun provideOnboardingClock(): OnboardingClock = OnboardingClock {
        System.currentTimeMillis()
    }
}