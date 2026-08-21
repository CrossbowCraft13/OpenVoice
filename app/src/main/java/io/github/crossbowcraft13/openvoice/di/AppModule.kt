package io.github.crossbowcraft13.openvoice.di

import android.content.Context
import io.github.crossbowcraft13.openvoice.audio.AudioCaptureManager
import io.github.crossbowcraft13.openvoice.intent.IntentClassifier
import io.github.crossbowcraft13.openvoice.operator.OperatorRegistry
import io.github.crossbowcraft13.openvoice.pipeline.VoicePipeline
import io.github.crossbowcraft13.openvoice.router.CapabilityRouter
import io.github.crossbowcraft13.openvoice.developer.DeveloperConsole
import io.github.crossbowcraft13.openvoice.perception.PerceptionEngine
import io.github.crossbowcraft13.openvoice.stt.WhisperSttEngine
import io.github.crossbowcraft13.openvoice.tts.PiperTtsEngine
import io.github.crossbowcraft13.openvoice.vad.VadManager
import io.github.crossbowcraft13.openvoice.wakeword.WakeWordDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)object AppModule {

    @Provides @Singleton
    fun provideAudioCapture(@ApplicationContext ctx: Context) = AudioCaptureManager(ctx)

    @Provides @Singleton
    fun provideVadManager(@ApplicationContext ctx: Context) = VadManager(ctx)

    @Provides @Singleton
    fun provideWakeWordDetector(@ApplicationContext ctx: Context) = WakeWordDetector(ctx)

    @Provides @Singleton
    fun provideSttEngine(@ApplicationContext ctx: Context) = WhisperSttEngine(ctx)

    @Provides @Singleton
    fun provideTtsEngine(@ApplicationContext ctx: Context) = PiperTtsEngine(ctx)

    @Provides @Singleton fun provideCapabilityRouter() = CapabilityRouter()
    @Provides @Singleton fun provideIntentClassifier() = IntentClassifier()
    @Provides @Singleton fun provideOperatorRegistry() = OperatorRegistry()

    @Provides @Singleton
    fun provideVoicePipeline(
        @ApplicationContext ctx: Context, audio: AudioCaptureManager, vad: VadManager,
        ww: WakeWordDetector, stt: WhisperSttEngine, tts: PiperTtsEngine,
        intent: IntentClassifier, ops: OperatorRegistry, router: CapabilityRouter,
        console: DeveloperConsole, perception: PerceptionEngine
    ) = VoicePipeline(ctx, audio, vad, ww, stt, tts, intent, ops, router, console, perception)
}
