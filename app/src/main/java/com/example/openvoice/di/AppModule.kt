package com.example.openvoice.di

import android.content.Context
import com.example.openvoice.audio.AudioCaptureManager
import com.example.openvoice.intent.IntentClassifier
import com.example.openvoice.operator.OperatorRegistry
import com.example.openvoice.pipeline.VoicePipeline
import com.example.openvoice.router.CapabilityRouter
import com.example.openvoice.developer.DeveloperConsole
import com.example.openvoice.perception.PerceptionEngine
import com.example.openvoice.stt.WhisperSttEngine
import com.example.openvoice.tts.PiperTtsEngine
import com.example.openvoice.vad.VadManager
import com.example.openvoice.wakeword.WakeWordDetector
import com.example.openvoice.util.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton fun provideLogger(): Logger { Logger.init(true); return Logger }

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
