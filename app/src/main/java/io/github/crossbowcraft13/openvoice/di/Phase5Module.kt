package io.github.crossbowcraft13.openvoice.di

import android.content.Context
import io.github.crossbowcraft13.openvoice.perception.PerceptionEngine
import io.github.crossbowcraft13.openvoice.perception.ScreenshotPipeline
import io.github.crossbowcraft13.openvoice.perception.OcrEngine
import io.github.crossbowcraft13.openvoice.perception.VisualMemoryCache
import io.github.crossbowcraft13.openvoice.perception.vision.VisionRuntime
import io.github.crossbowcraft13.openvoice.task.TaskBlackboard
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object Phase5Module {

    @Provides @Singleton
    fun provideScreenshotPipeline(@ApplicationContext ctx: Context) = ScreenshotPipeline(ctx)

    @Provides @Singleton
    fun provideOcrEngine(@ApplicationContext ctx: Context) = OcrEngine(ctx)

    @Provides @Singleton
    fun provideVisualMemoryCache() = VisualMemoryCache()

    @Provides @Singleton
    fun providePerceptionEngine(
        screenshot: ScreenshotPipeline,
        ocr: OcrEngine,
        vision: VisionRuntime,
        cache: VisualMemoryCache,
        blackboard: TaskBlackboard
    ) = PerceptionEngine(screenshot, ocr, vision, cache, blackboard)
}
