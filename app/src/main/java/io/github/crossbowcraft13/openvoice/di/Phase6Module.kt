package io.github.crossbowcraft13.openvoice.di

import android.content.Context
import io.github.crossbowcraft13.openvoice.ai.InferenceEngine
import io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph
import io.github.crossbowcraft13.openvoice.memory.MemoryEngine
import io.github.crossbowcraft13.openvoice.memory.MemoryLifecycle
import io.github.crossbowcraft13.openvoice.memory.VectorStore
import io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption
import io.github.crossbowcraft13.openvoice.task.TaskBlackboard
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object Phase6Module {

    @Provides @Singleton
    fun provideKnowledgeGraph(@ApplicationContext ctx: Context) = KnowledgeGraph(ctx)

    @Provides @Singleton
    fun provideVectorStore(
        @ApplicationContext ctx: Context,
        engine: InferenceEngine
    ) = VectorStore(ctx, engine)

    @Provides @Singleton
    fun provideMemoryEncryption(@ApplicationContext ctx: Context) = MemoryEncryption(ctx)

    @Provides @Singleton
    fun provideMemoryLifecycle(
        @ApplicationContext ctx: Context,
        engine: InferenceEngine,
        encryption: MemoryEncryption,
        vectorStore: VectorStore,
        knowledgeGraph: KnowledgeGraph
    ) = MemoryLifecycle(ctx, engine, encryption, vectorStore, knowledgeGraph)

    @Provides @Singleton
    fun provideMemoryEngine(
        @ApplicationContext ctx: Context,
        vectorStore: VectorStore,
        knowledgeGraph: KnowledgeGraph,
        encryption: MemoryEncryption,
        lifecycle: MemoryLifecycle,
        blackboard: TaskBlackboard
    ) = MemoryEngine(ctx, vectorStore, knowledgeGraph, encryption, lifecycle, blackboard)
}
