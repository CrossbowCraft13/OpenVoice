package com.example.openvoice.di

import com.example.openvoice.accessibility.engine.AccessibilityIntelligence
import com.example.openvoice.accessibility.engine.UiSearchEngine
import com.example.openvoice.task.TaskBlackboard
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object Phase4Module {

    @Provides @Singleton
    fun provideTaskBlackboard() = TaskBlackboard()

    @Provides @Singleton
    fun provideUiSearchEngine() = UiSearchEngine()

    // AccessibilityIntelligence is not provided as a singleton here
    // because it requires VoiceAccessibilityService (bound to the service lifecycle).
    // It is created by VoiceAccessibilityService.onCreate().
}
