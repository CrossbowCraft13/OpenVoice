package io.github.crossbowcraft13.openvoice.di

import android.content.Context
import io.github.crossbowcraft13.openvoice.ai.AiSettings
import io.github.crossbowcraft13.openvoice.ai.DeviceProfiler
import io.github.crossbowcraft13.openvoice.ai.InferenceBackend
import io.github.crossbowcraft13.openvoice.ai.InferenceEngine
import io.github.crossbowcraft13.openvoice.ai.LlamaCppBridge
import io.github.crossbowcraft13.openvoice.ai.ModelManager
import io.github.crossbowcraft13.openvoice.developer.DeveloperConsole
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
    fun provideInferenceBackend(): InferenceBackend = LlamaCppBridge

    @Provides @Singleton
    fun provideInferenceEngine(
        @ApplicationContext ctx: Context,
        settings: AiSettings,
        profiler: DeviceProfiler,
        backend: InferenceBackend
    ) = InferenceEngine(ctx, settings, profiler, backend)

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
        router: io.github.crossbowcraft13.openvoice.router.CapabilityRouter
    ) = DeveloperConsole(profiler, engine, modelManager, router)
}
