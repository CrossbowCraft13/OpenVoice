package com.example.openvoice.di

import android.content.Context
import com.example.openvoice.perception.PerceptionEngine
import com.example.openvoice.perception.ScreenshotPipeline
import com.example.openvoice.perception.OcrEngine
import com.example.openvoice.perception.VisualMemoryCache
import com.example.openvoice.perception.vision.VisionRuntime
import com.example.openvoice.task.TaskBlackboard
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
