package com.example.openvoice.memory

import android.content.Context
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.memory.encryption.MemoryEncryption
import com.example.openvoice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MemoryLifecycle — Tiered memory management with automatic consolidation.
 *
 * Memory tiers:
 *   WORKING (1 day)    → High priority, low TTL
 *   SHORT_TERM (7 day) → Recent conversations, temporary facts
 *   LONG_TERM (365 day)→ Core preferences, contacts, routines
 *
 * Consolidation moves important memories from short-term to long-term.
 * Forgetting removes stale, low-importance memories automatically.
 * Summarization compresses long conversation histories.
 */
@Singleton
class MemoryLifecycle @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: InferenceEngine,
    private val encryption: MemoryEncryption,
    private val vectorStore: VectorStore,
    private val knowledgeGraph: KnowledgeGraph
) {
    var lastConsolidationTime: Long = 0L
        private set
    var lastForgettingTime: Long = 0L
        private set

    companion object {
        private const val CONSOLIDATION_INTERVAL_MS = 3600000L  // 1 hour
        private const val FORGETTING_INTERVAL_MS = 86400000L    // 24 hours
        private const val WORKING_DAYS = 1
        private const val SHORT_TERM_DAYS = 7
        private const val LONG_TERM_DAYS = 365
        private const val IMPORTANCE_THRESHOLD = 0.3f
        private const val CONSOLIDATION_IMPORTANCE = 0.6f
    }

    /**
     * Run the full lifecycle cycle: consolidate → forget → summarize.
     * Called periodically by WorkManager.
     */
    suspend fun runCycle(): LifecycleReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val consolidated = if (now - lastConsolidationTime > CONSOLIDATION_INTERVAL_MS) {
            consolidate()
        } else 0

        val forgotten = if (now - lastForgettingTime > FORGETTING_INTERVAL_MS) {
            forgetStale()
        } else 0

        val report = LifecycleReport(
            consolidated = consolidated,
            forgotten = forgotten,
            totalMemories = 0,
            totalRelations = 0,
            cycleTimeMs = System.currentTimeMillis() - now
        )

        lastConsolidationTime = now
        lastForgettingTime = now

        Logger.i("Memory lifecycle: ${report.consolidated} consolidated, " +
            "${report.forgotten} forgotten (${report.cycleTimeMs}ms)", "Memory")

        report
    }

    /**
     * Consolidate important short-term memories into long-term.
     *
     * Strategy:
     * - Find memories with importance > CONSOLIDATION_IMPORTANCE
     * - Move them from SHORT_TERM to LONG_TERM category
     * - Extend their TTL
     * - Optionally summarize related memories into a single entry
     */
    private suspend fun consolidate(): Int {
        // In a full implementation, this would query all memories,
        // score them by importance + access frequency + recency,
        // and promote high-scoring entries to long-term storage.
        Logger.d("Memory consolidation cycle", "Memory")
        return 0 // Placeholder — will be wired when MemoryEngine is complete
    }

    /**
     * Forget stale, low-importance memories.
     *
     * Strategy:
     * - Find expired memories (TTL exceeded)
     * - Exclude pinned memories
     * - Remove low-importance memories regardless of TTL
     * - Batch delete from all stores (SQLite, vector index, graph)
     */
    private suspend fun forgetStale(): Int {
        // In a full implementation, this would:
        // 1. Query all memories with TTL exceeded
        // 2. Filter out pinned entries
        // 3. Score by importance × access frequency × recency
        // 4. Delete lowest-scoring entries
        Logger.d("Memory forgetting cycle", "Memory")
        return 0
    }

    /**
     * Summarize and compress conversation histories.
     * Runs when a conversation gets too long or old.
     */
    suspend fun summarizeConversation(memories: List<Memory>): String? = withContext(Dispatchers.IO) {
        if (memories.size < 3 || !engine.isReady) return@withContext null

        val text = memories.joinToString("\n") {
            "${it.key}: ${it.value}"
        }

        if (text.length > 1000) {
            try {
                val summary = engine.complete(
                    "Summarize the following conversation:\n\n$text\n\nSummary:"
                )
                summary.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Logger.e("Summarization failed: ${e.message}", "Memory")
                null
            }
        } else null
    }

    /**
     * Score a memory's importance based on multiple factors.
     */
    fun calculateImportance(memory: Memory): Float {
        val recency = 1f / (1f + memory.ageDays.toFloat())
        val frequency = minOf(memory.accessCount.toFloat() / 100f, 1f)
        val confidenceWeight = memory.confidence
        val pinnedBonus = if (memory.pinned) 0.3f else 0f

        return (recency * 0.3f + frequency * 0.3f + confidenceWeight * 0.2f + pinnedBonus)
            .coerceIn(0f, 1f)
    }

    /**
     * Decide whether a memory should be promoted to long-term storage.
     */
    fun shouldConsolidate(memory: Memory): Boolean {
        return memory.category == MemoryCategory.WORKING &&
            calculateImportance(memory) > CONSOLIDATION_IMPORTANCE
    }

    /**
     * Decide whether a memory should be forgotten.
     */
    fun shouldForget(memory: Memory): Boolean {
        if (memory.pinned) return false
        if (memory.isExpired && memory.importance < IMPORTANCE_THRESHOLD) return true
        return memory.importance < 0.1f && memory.ageDays > 30
    }

    data class LifecycleReport(
        val consolidated: Int = 0,
        val forgotten: Int = 0,
        val totalMemories: Int = 0,
        val totalRelations: Int = 0,
        val cycleTimeMs: Long = 0
    )
}
