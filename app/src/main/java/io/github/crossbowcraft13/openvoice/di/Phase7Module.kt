package io.github.crossbowcraft13.openvoice.di

import android.content.Context
import io.github.crossbowcraft13.openvoice.intent.IntentClassifier
import io.github.crossbowcraft13.openvoice.memory.MemoryEngine
import io.github.crossbowcraft13.openvoice.operator.OperatorRegistry
import io.github.crossbowcraft13.openvoice.perception.PerceptionEngine
import io.github.crossbowcraft13.openvoice.planner.*
import io.github.crossbowcraft13.openvoice.task.TaskBlackboard
import io.github.crossbowcraft13.openvoice.ai.InferenceEngine
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
