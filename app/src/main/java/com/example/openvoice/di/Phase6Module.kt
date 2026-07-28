package com.example.openvoice.di

import android.content.Context
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.memory.KnowledgeGraph
import com.example.openvoice.memory.MemoryEngine
import com.example.openvoice.memory.MemoryLifecycle
import com.example.openvoice.memory.VectorStore
import com.example.openvoice.memory.encryption.MemoryEncryption
import com.example.openvoice.task.TaskBlackboard
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
