package com.example.openvoice.di

import android.content.Context
import com.example.openvoice.system.explain.ExplainMode
import com.example.openvoice.system.ResourceManager
import com.example.openvoice.system.ReliabilityManager
import com.example.openvoice.system.SystemOrchestrator
import com.example.openvoice.system.PrivacyDashboard
import com.example.openvoice.system.onboarding.OnboardingFlow
import com.example.openvoice.util.PermissionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object Phase8Module {

    @Provides @Singleton
    fun provideExplainMode(blackboard: com.example.openvoice.task.TaskBlackboard,
                           memory: com.example.openvoice.memory.MemoryEngine) = ExplainMode(blackboard, memory)

    @Provides @Singleton
    fun provideResourceManager(@ApplicationContext ctx: Context,
                               profiler: com.example.openvoice.ai.DeviceProfiler) = ResourceManager(ctx, profiler)

    @Provides @Singleton
    fun provideReliabilityManager(@ApplicationContext ctx: Context,
                                  blackboard: com.example.openvoice.task.TaskBlackboard) = ReliabilityManager(ctx, blackboard)

    @Provides @Singleton
    fun providePermissionManager(@ApplicationContext ctx: Context) = PermissionManager(ctx)

    @Provides @Singleton
    fun providePrivacyDashboard(@ApplicationContext ctx: Context,
                                memory: com.example.openvoice.memory.MemoryEngine,
                                permMgr: PermissionManager) = PrivacyDashboard(ctx, memory, permMgr)

    @Provides @Singleton
    fun provideOnboardingFlow(@ApplicationContext ctx: Context,
                              permMgr: PermissionManager) = OnboardingFlow(ctx, permMgr)
}
