package id.warung.monitor.feature.onboarding.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import id.warung.monitor.feature.onboarding.data.DataStoreOnboardingStore
import id.warung.monitor.feature.onboarding.data.OnboardingStore
import id.warung.monitor.feature.onboarding.data.onboardingDataStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OnboardingModule {

    @Provides
    @Singleton
    fun provideOnboardingStore(@ApplicationContext context: Context): OnboardingStore =
        DataStoreOnboardingStore(context.onboardingDataStore)
}