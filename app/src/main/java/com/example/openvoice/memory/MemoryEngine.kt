package com.example.openvoice.memory

import android.content.Context
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.memory.encryption.MemoryEncryption
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MemoryEngine — THE single public interface for long-term memory.
 *
 * Every module (planner, skills, vision, LLM) uses MemoryEngine instead
 * of accessing storage directly.
 *
 * Capabilities:
 * - Store / Retrieve / Update / Delete / Forget
 * - Semantic search (via VectorStore)
 * - Graph traversal (via KnowledgeGraph)
 * - Hybrid search (vector + graph combined)
 * - By category, keyword, time range
 * - Export / Import
 * - Lifecycle (consolidation, forgetting)
 *
 * Retrieval pipeline:
 *   1. Key-value lookup (exact match, fastest)
 *   2. Keyword search (SQL LIKE)
 *   3. Graph traversal (related facts)
 *   4. Vector similarity (semantic)
 *   5. Hybrid (vector + graph combined)
 */
@Singleton
class MemoryEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vectorStore: VectorStore,
    private val knowledgeGraph: KnowledgeGraph,
    private val encryption: MemoryEncryption,
    private val lifecycle: MemoryLifecycle,
    private val blackboard: TaskBlackboard
) {
    private var initialized = false
    private val memoryDb by lazy { initMemoryDb() }

    private fun initMemoryDb(): android.database.sqlite.SQLiteDatabase {
        return context.openOrCreateDatabase("openvoice_memories.db", Context.MODE_PRIVATE, null).apply {
            execSQL("""
                CREATE TABLE IF NOT EXISTS memories (
                    id TEXT PRIMARY KEY,
                    created_at INTEGER DEFAULT 0,
                    updated_at INTEGER DEFAULT 0,
                    accessed_at INTEGER DEFAULT 0,
                    source TEXT DEFAULT 'assistant',
                    confidence REAL DEFAULT 1.0,
                    category TEXT DEFAULT 'LEARNED_FACT',
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    summary TEXT DEFAULT '',
                    ttl_days INTEGER DEFAULT 365,
                    importance REAL DEFAULT 0.5,
                    access_count INTEGER DEFAULT 0,
                    pinned INTEGER DEFAULT 0,
                    encrypted INTEGER DEFAULT 0
                )
            """)
            execSQL("CREATE INDEX IF NOT EXISTS idx_mem_key ON memories(key)")
            execSQL("CREATE INDEX IF NOT EXISTS idx_mem_cat ON memories(category)")
            execSQL("CREATE INDEX IF NOT EXISTS idx_mem_imp ON memories(importance)")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_mem_uk ON memories(key, category)")
        }
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            memoryDb  // Force init
            knowledgeGraph.initialize()
            vectorStore.initialize()
            encryption.initialize()
            initialized = true
            Logger.i("MemoryEngine initialized", "Memory")
            true
        } catch (e: Exception) {
            Logger.e("MemoryEngine init failed: ${e.message}", "Memory")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  CRUD Operations
    // ═══════════════════════════════════════════════════════════════

    suspend fun store(memory: Memory): Boolean = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext false
        try {
            val valueToStore = if (memory.encrypted) {
                encryption.encryptString(memory.value) ?: memory.value
            } else memory.value

            memoryDb.execSQL("""
                INSERT OR REPLACE INTO memories
                (id, created_at, updated_at, accessed_at, source, confidence, category,
                 key, value, summary, ttl_days, importance, access_count, pinned, encrypted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, arrayOf(
                memory.id, memory.createdAt.toString(), memory.updatedAt.toString(),
                memory.accessedAt.toString(), memory.source, memory.confidence.toString(),
                memory.category.name, memory.key, valueToStore, memory.summary,
                memory.ttlDays.toString(), memory.importance.toString(),
                memory.accessCount.toString(), if (memory.pinned) "1" else "0",
                if (memory.encrypted) "1" else "0"
            ))

            // Index in vector store
            if (memory.embedding != null || memory.category != MemoryCategory.WORKING) {
                vectorStore.index(memory)
            }

            // Store relationships in knowledge graph
            for (rel in memory.relationships) {
                knowledgeGraph.store(rel)
            }

            Logger.d("Stored: ${memory.key} (${memory.category.displayName})", "Memory")
            true
        } catch (e: Exception) {
            Logger.e("Store error: ${e.message}", "Memory")
            false
        }
    }

    /** Store a simple key-value memory with auto-categorization. */
    suspend fun remember(key: String, value: String, category: MemoryCategory = MemoryCategory.LEARNED_FACT,
                          confidence: Float = 1.0f, source: String = "assistant",
                          relations: List<Relation> = emptyList()): Boolean {
        return store(Memory(
            key = key,
            value = value,
            category = category,
            confidence = confidence,
            source = source,
            relationships = relations
        ))
    }

    /** Remember a preference (convenience method). */
    suspend fun rememberPreference(key: String, value: String): Boolean =
        remember(key, value, MemoryCategory.USER_PREFERENCE, source = "user")

    /** Remember a contact (convenience method). */
    suspend fun rememberContact(name: String, value: String): Boolean =
        remember("contact_$name", value, MemoryCategory.CONTACT, source = "user",
            relations = listOf(Relation(
                subject = "user", predicate = "knows", obj = name
            )))

    /** Remember a routine (convenience method). */
    suspend fun rememberRoutine(name: String, steps: String): Boolean =
        remember("routine_$name", steps, MemoryCategory.ROUTINE, source = "assistant")

    suspend fun retrieve(key: String): Memory? = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext null
        try {
            val cursor = memoryDb.rawQuery(
                "SELECT * FROM memories WHERE key = ? LIMIT 1", arrayOf(key))
            if (cursor.moveToFirst()) {
                val mem = cursorToMemory(cursor)
                incrementAccess(mem.id)
                cursor.close()
                mem
            } else { cursor.close(); null }
        } catch (e: Exception) { null }
    }

    suspend fun retrieveById(id: String): Memory? = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext null
        try {
            val cursor = memoryDb.rawQuery(
                "SELECT * FROM memories WHERE id = ? LIMIT 1", arrayOf(id))
            if (cursor.moveToFirst()) {
                val mem = cursorToMemory(cursor)
                incrementAccess(mem.id)
                cursor.close()
                mem
            } else { cursor.close(); null }
        } catch (e: Exception) { null }
    }

    suspend fun update(memory: Memory): Boolean = withContext(Dispatchers.IO) {
        store(memory.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext false
        try {
            memoryDb.execSQL("DELETE FROM memories WHERE id = ?", arrayOf(id))
            vectorStore.remove(id)
            true
        } catch (e: Exception) { false }
    }

    suspend fun deleteByKey(key: String): Boolean = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext false
        try {
            // Find the memory ID first
            val cursor = memoryDb.rawQuery("SELECT id FROM memories WHERE key = ?", arrayOf(key))
            val ids = mutableListOf<String>()
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
            cursor.close()

            memoryDb.execSQL("DELETE FROM memories WHERE key = ?", arrayOf(key))
            ids.forEach { vectorStore.remove(it) }

            // Also remove graph relations
            knowledgeGraph.deleteSubject(key)
            true
        } catch (e: Exception) { false }
    }

    suspend fun forget(key: String): Boolean = deleteByKey(key)

    // ═══════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════

    suspend fun searchByKeyword(query: String, limit: Int = 10): List<Memory> = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext emptyList()
        try {
            val pattern = "%$query%"
            val cursor = memoryDb.rawQuery("""
                SELECT * FROM memories WHERE key LIKE ? OR value LIKE ? OR summary LIKE ?
                ORDER BY importance DESC, accessed_at DESC LIMIT ?
            """, arrayOf(pattern, pattern, pattern, limit.toString()))

            cursorToMemoryList(cursor)
        } catch (e: Exception) { emptyList() }
    }

    suspend fun searchByCategory(category: MemoryCategory, limit: Int = 20): List<Memory> =
        withContext(Dispatchers.IO) {
            if (!initialized) return@withContext emptyList()
            try {
                val cursor = memoryDb.rawQuery("""
                    SELECT * FROM memories WHERE category = ?
                    ORDER BY importance DESC, accessed_at DESC LIMIT ?
                """, arrayOf(category.name, limit.toString()))
                cursorToMemoryList(cursor)
            } catch (e: Exception) { emptyList() }
        }

    suspend fun searchByTimeRange(fromMs: Long, toMs: Long, limit: Int = 50): List<Memory> =
        withContext(Dispatchers.IO) {
            if (!initialized) return@withContext emptyList()
            try {
                val cursor = memoryDb.rawQuery("""
                    SELECT * FROM memories WHERE created_at BETWEEN ? AND ?
                    ORDER BY created_at DESC LIMIT ?
                """, arrayOf(fromMs.toString(), toMs.toString(), limit.toString()))
                cursorToMemoryList(cursor)
            } catch (e: Exception) { emptyList() }
        }

    /**
     * Semantic search using vector embeddings.
     */
    suspend fun searchSemantic(query: String, topK: Int = 5, category: MemoryCategory? = null): MemoryResult {
        val start = System.currentTimeMillis()
        val results = vectorStore.search(query, topK, category)
        val memories = results.mapNotNull { retrieveById(it.memoryId) }

        return MemoryResult(
            memories = memories,
            query = query,
            latencyMs = System.currentTimeMillis() - start,
            retrievalType = RetrievalType.SEMANTIC,
            source = "vector"
        )
    }

    /**
     * Graph-based search — find facts related to a concept.
     */
    suspend fun searchGraph(query: String, depth: Int = 1): MemoryResult {
        val start = System.currentTimeMillis()

        // Find the concept in the graph
        val relations = knowledgeGraph.findRelated(query)
        // Get the subgraph
        val subgraph = knowledgeGraph.getSubgraph(query, depth)

        // Retrieve associated memories
        val relatedKeys = relations.map { it.obj } + relations.map { it.subject }
        val memories = relatedKeys.distinct().mapNotNull { retrieve(it) }

        return MemoryResult(
            memories = memories,
            relations = subgraph,
            query = query,
            latencyMs = System.currentTimeMillis() - start,
            retrievalType = RetrievalType.GRAPH,
            source = "graph"
        )
    }

    /**
     * Hybrid search — vector + graph combined.
     * Retrieves semantically similar memories AND directly related facts.
     */
    suspend fun searchHybrid(query: String, topK: Int = 5, depth: Int = 1): MemoryResult {
        val start = System.currentTimeMillis()
        val seen = mutableSetOf<String>()

        // 1. Vector search
        val vectorResult = searchSemantic(query, topK)

        // 2. Graph search from vector results' keys
        val graphRelations = mutableListOf<Relation>()
        val graphMemories = mutableListOf<Memory>()

        for (mem in vectorResult.memories) {
            val subgraph = knowledgeGraph.findRelated(mem.key)
            graphRelations.addAll(subgraph)

            val relatedKeys = subgraph.map { it.obj } + subgraph.map { it.subject }
            for (key in relatedKeys.distinct()) {
                if (key !in seen && key != mem.key) {
                    seen.add(key)
                    retrieve(key)?.let { graphMemories.add(it) }
                }
            }
        }

        val allMemories = (vectorResult.memories + graphMemories).distinctBy { it.id }

        return MemoryResult(
            memories = allMemories,
            relations = (vectorResult.relations + graphRelations).distinctBy { it.id },
            query = query,
            latencyMs = System.currentTimeMillis() - start,
            retrievalType = RetrievalType.HYBRID,
            source = "hybrid"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  User Control
    // ═══════════════════════════════════════════════════════════════

    suspend fun getAllMemories(limit: Int = 100): List<Memory> = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext emptyList()
        try {
            val cursor = memoryDb.rawQuery("""
                SELECT * FROM memories ORDER BY importance DESC, created_at DESC LIMIT ?
            """, arrayOf(limit.toString()))
            cursorToMemoryList(cursor)
        } catch (e: Exception) { emptyList() }
    }

    suspend fun getPinnedMemories(): List<Memory> = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext emptyList()
        try {
            val cursor = memoryDb.rawQuery(
                "SELECT * FROM memories WHERE pinned = 1 ORDER BY created_at DESC", null)
            cursorToMemoryList(cursor)
        } catch (e: Exception) { emptyList() }
    }

    suspend fun pinMemory(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            memoryDb.execSQL("UPDATE memories SET pinned = 1 WHERE id = ?", arrayOf(id))
            true
        } catch (e: Exception) { false }
    }

    suspend fun unpinMemory(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            memoryDb.execSQL("UPDATE memories SET pinned = 0 WHERE id = ?", arrayOf(id))
            true
        } catch (e: Exception) { false }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Export / Import
    // ═══════════════════════════════════════════════════════════════

    suspend fun exportAll(): MemoryExport = withContext(Dispatchers.IO) {
        val memories = getAllMemories(Int.MAX_VALUE)
        val relations = knowledgeGraph.exportAll()
        val json = JSONObject().apply {
            put("version", 1)
            put("createdAt", System.currentTimeMillis())
            put("memoryCount", memories.size)
            put("relationCount", relations.size)
        }
        val content = json.toString()
        val checksum = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }

        MemoryExport(
            memories = memories,
            relations = relations,
            checksum = checksum
        )
    }

    suspend fun importExport(export: MemoryExport): Boolean = withContext(Dispatchers.IO) {
        try {
            memoryDb.beginTransaction()
            for (mem in export.memories) store(mem)
            knowledgeGraph.importAll(export.relations)
            memoryDb.setTransactionSuccessful()
            memoryDb.endTransaction()
            Logger.i("Imported ${export.memories.size} memories, ${export.relations.size} relations", "Memory")
            true
        } catch (e: Exception) {
            Logger.e("Import failed: ${e.message}", "Memory")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Stats
    // ═══════════════════════════════════════════════════════════════

    suspend fun getStats(): MemoryStats = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext MemoryStats()
        try {
            val cursor = memoryDb.rawQuery("""
                SELECT category, COUNT(*) as cnt, AVG(confidence) as avg_conf,
                       MIN(created_at) as oldest, MAX(created_at) as newest
                FROM memories GROUP BY category
            """, null)

            val byCategory = mutableMapOf<MemoryCategory, Int>()
            var total = 0
            var avgConf = 0f
            var oldest = System.currentTimeMillis()
            var newest = 0L

            while (cursor.moveToNext()) {
                val cat = try { MemoryCategory.valueOf(cursor.getString(0)) }
                catch (_: Exception) { null }
                val count = cursor.getInt(1)
                if (cat != null) byCategory[cat] = count
                total += count
                avgConf += cursor.getFloat(2) * count
                oldest = minOf(oldest, cursor.getLong(3))
                newest = maxOf(newest, cursor.getLong(4))
            }
            cursor.close()

            val relCount = knowledgeGraph.count().toInt()

            MemoryStats(
                totalMemories = total,
                totalRelations = relCount,
                byCategory = byCategory,
                oldest = oldest,
                newest = newest,
                averageConfidence = if (total > 0) avgConf / total else 0f,
                lastConsolidation = lifecycle.lastConsolidationTime
            )
        } catch (e: Exception) { MemoryStats() }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        try {
            val cursor = memoryDb.rawQuery("SELECT COUNT(*) FROM memories", null)
            cursor.moveToFirst()
            val c = cursor.getInt(0)
            cursor.close(); c
        } catch (e: Exception) { 0 }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Private Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun incrementAccess(id: String) {
        try {
            memoryDb.execSQL("""
                UPDATE memories SET access_count = access_count + 1, accessed_at = ?
                WHERE id = ?
            """, arrayOf(System.currentTimeMillis().toString(), id))
        } catch (_: Exception) {}
    }

    private fun cursorToMemory(cursor: android.database.Cursor): Memory {
        val encrypted = cursor.getInt(14) == 1
        var value = cursor.getString(9)
        if (encrypted) {
            val decrypted = encryption.decryptString(value)
            if (decrypted != null) value = decrypted
        }

        return Memory(
            id = cursor.getString(0),
            createdAt = cursor.getLong(1),
            updatedAt = cursor.getLong(2),
            accessedAt = cursor.getLong(3),
            source = cursor.getString(4),
            confidence = cursor.getFloat(5),
            category = try { MemoryCategory.valueOf(cursor.getString(6)) }
            catch (_: Exception) { MemoryCategory.LEARNED_FACT },
            key = cursor.getString(7),
            value = value,
            summary = cursor.getString(10),
            ttlDays = cursor.getInt(11),
            importance = cursor.getFloat(12),
            accessCount = cursor.getInt(13),
            pinned = cursor.getInt(14) == 1,
            encrypted = encrypted
        )
    }

    private fun cursorToMemoryList(cursor: android.database.Cursor): List<Memory> {
        val results = mutableListOf<Memory>()
        while (cursor.moveToNext()) {
            results.add(cursorToMemory(cursor))
        }
        cursor.close()
        return results
    }

    fun getLastConsolidationTime(): Long = lifecycle.lastConsolidationTime
}
