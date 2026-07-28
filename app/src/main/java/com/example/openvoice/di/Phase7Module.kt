package com.example.openvoice.di

import android.content.Context
import com.example.openvoice.intent.IntentClassifier
import com.example.openvoice.memory.MemoryEngine
import com.example.openvoice.operator.OperatorRegistry
import com.example.openvoice.perception.PerceptionEngine
import com.example.openvoice.planner.*
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.ai.InferenceEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object Phase7Module {

    @Provides @Singleton
    fun provideCostModel() = CostModel()

    @Provides @Singleton
    fun provideUserConfirmationPolicy() = UserConfirmationPolicy()

    @Provides @Singleton
    fun provideDynamicReplanner(costModel: CostModel) = DynamicReplanner(costModel)

    @Provides @Singleton
    fun providePlanner(
        intentClassifier: IntentClassifier,
        costModel: CostModel,
        operatorRegistry: OperatorRegistry,
        perceptionEngine: PerceptionEngine,
        memoryEngine: MemoryEngine,
        blackboard: TaskBlackboard
    ) = Planner(intentClassifier, costModel, operatorRegistry, perceptionEngine, memoryEngine, blackboard)

    @Provides @Singleton
    fun provideLearningRecorder(memoryEngine: MemoryEngine) = LearningRecorder(memoryEngine)

    @Provides @Singleton
    fun provideExecutionEngine(
        @ApplicationContext ctx: Context,
        operatorRegistry: OperatorRegistry,
        perceptionEngine: PerceptionEngine,
        memoryEngine: MemoryEngine,
        inferenceEngine: InferenceEngine,
        blackboard: TaskBlackboard,
        replanner: DynamicReplanner
    ) = ExecutionEngine(ctx, operatorRegistry, perceptionEngine, memoryEngine, inferenceEngine, blackboard, replanner)
}
