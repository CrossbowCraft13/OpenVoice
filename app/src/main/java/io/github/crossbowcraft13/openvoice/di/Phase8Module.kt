package io.github.crossbowcraft13.openvoice.di

import android.content.Context
import io.github.crossbowcraft13.openvoice.system.explain.ExplainMode
import io.github.crossbowcraft13.openvoice.system.ResourceManager
import io.github.crossbowcraft13.openvoice.system.ReliabilityManager
import io.github.crossbowcraft13.openvoice.system.SystemOrchestrator
import io.github.crossbowcraft13.openvoice.system.PrivacyDashboard
import io.github.crossbowcraft13.openvoice.system.onboarding.OnboardingFlow
import io.github.crossbowcraft13.openvoice.util.PermissionManager
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
    fun provideExplainMode(blackboard: io.github.crossbowcraft13.openvoice.task.TaskBlackboard,
                           memory: io.github.crossbowcraft13.openvoice.memory.MemoryEngine) = ExplainMode(blackboard, memory)

    @Provides @Singleton
    fun provideResourceManager(@ApplicationContext ctx: Context,
                               profiler: io.github.crossbowcraft13.openvoice.ai.DeviceProfiler) = ResourceManager(ctx, profiler)

    @Provides @Singleton
    fun provideReliabilityManager(@ApplicationContext ctx: Context,
                                  blackboard: io.github.crossbowcraft13.openvoice.task.TaskBlackboard) = ReliabilityManager(ctx, blackboard)

    @Provides @Singleton
    fun providePermissionManager(@ApplicationContext ctx: Context) = PermissionManager(ctx)

    @Provides @Singleton
    fun providePrivacyDashboard(@ApplicationContext ctx: Context,
                                memory: io.github.crossbowcraft13.openvoice.memory.MemoryEngine,
                                permMgr: PermissionManager) = PrivacyDashboard(ctx, memory, permMgr)

    @Provides @Singleton
    fun provideOnboardingFlow(@ApplicationContext ctx: Context,
                              permMgr: PermissionManager) = OnboardingFlow(ctx, permMgr)
}
