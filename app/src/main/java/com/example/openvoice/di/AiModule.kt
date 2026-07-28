package com.example.openvoice.di

import android.content.Context
import com.example.openvoice.ai.AiSettings
import com.example.openvoice.ai.DeviceProfiler
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.ai.ModelManager
import com.example.openvoice.developer.DeveloperConsole
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides @Singleton
    fun provideDeviceProfiler(@ApplicationContext ctx: Context) = DeviceProfiler(ctx)

    @Provides @Singleton
    fun provideAiSettings(@ApplicationContext ctx: Context) = AiSettings(ctx)

    @Provides @Singleton
    fun provideInferenceEngine(
        @ApplicationContext ctx: Context,
        settings: AiSettings,
        profiler: DeviceProfiler
    ) = InferenceEngine(ctx, settings, profiler)

    @Provides @Singleton
    fun provideModelManager(
        @ApplicationContext ctx: Context,
        profiler: DeviceProfiler,
        settings: AiSettings
    ) = ModelManager(ctx, profiler, settings)

    @Provides @Singleton
    fun provideDeveloperConsole(
        profiler: DeviceProfiler,
        engine: InferenceEngine,
        modelManager: ModelManager,
        router: com.example.openvoice.router.CapabilityRouter
    ) = DeveloperConsole(profiler, engine, modelManager, router)
}
