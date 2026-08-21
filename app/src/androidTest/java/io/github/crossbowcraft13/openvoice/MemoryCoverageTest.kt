package io.github.crossbowcraft13.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.crossbowcraft13.openvoice.ai.AiSettings
import io.github.crossbowcraft13.openvoice.ai.DeviceProfiler
import io.github.crossbowcraft13.openvoice.ai.InferenceEngine
import io.github.crossbowcraft13.openvoice.memory.Memory
import io.github.crossbowcraft13.openvoice.memory.MemoryCategory
import io.github.crossbowcraft13.openvoice.memory.MemoryEngine
import io.github.crossbowcraft13.openvoice.memory.MemoryLifecycle
import io.github.crossbowcraft13.openvoice.memory.Relation
import io.github.crossbowcraft13.openvoice.memory.RetrievalType
import io.github.crossbowcraft13.openvoice.memory.VectorStore
import io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption
import io.github.crossbowcraft13.openvoice.task.TaskBlackboard
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Coverage pass 3a — memory package.
 *
 * Drives the real MemoryEngine (SQLite), VectorStore (in-memory index via the
 * indexWithEmbedding seam), and MemoryLifecycle end to end on-device.
 */
@RunWith(AndroidJUnit4::class)
class MemoryEngineCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun buildEngine(encryptionInit: Boolean = true): Pair<MemoryEngine, MemoryEncryption> {
        // Fresh database files per test so CRUD state never leaks between tests.
        context.deleteDatabase("openvoice_memories.db")
        context.deleteDatabase("openvoice_knowledge.db")
        val settings = AiSettings(context)
        val profiler = DeviceProfiler(context)
        val inference = InferenceEngine(context, settings, profiler)
        val vectorStore = VectorStore(context, inference)
        val graph = io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(context)
        val encryption = MemoryEncryption(context)
        if (encryptionInit) encryption.initialize()
        val lifecycle = MemoryLifecycle(context, inference, encryption, vectorStore, graph)
        return MemoryEngine(context, vectorStore, graph, encryption, lifecycle, TaskBlackboard()) to encryption
    }

    // ── Initialize ────────────────────────────────────────────────

    @Test
    fun initialize_succeeds() = runBlocking {
        val (engine, _) = buildEngine()
        assertTrue(engine.initialize())
    }

    // ── CRUD ──────────────────────────────────────────────────────

    @Test
    fun store_then_retrieve_roundTrip() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        assertTrue(engine.store(Memory(key = "coffee", value = "black",
            category = MemoryCategory.USER_PREFERENCE, source = "user")))
        val mem = engine.retrieve("coffee")
        assertNotNull(mem)
        assertEquals("black", mem!!.value)
        assertEquals(MemoryCategory.USER_PREFERENCE, mem.category)
        assertEquals("user", mem.source)
    }

    @Test
    fun store_encrypted_roundTrip() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        assertTrue(engine.store(Memory(key = "secret", value = "hunter2", encrypted = true)))
        val mem = engine.retrieve("secret")
        assertNotNull(mem)
        assertEquals("hunter2", mem!!.value)
        assertTrue(mem.encrypted)
    }

    @Test
    fun remember_convenience_variants() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        assertTrue(engine.remember("fact", "the sky is blue"))
        assertTrue(engine.rememberPreference("theme", "dark"))
        assertTrue(engine.rememberContact("Alice", "555-0100"))
        assertTrue(engine.rememberRoutine("morning", "coffee then email"))

        assertEquals("the sky is blue", engine.retrieve("fact")!!.value)
        assertEquals("dark", engine.retrieve("theme")!!.value)
        assertEquals("555-0100", engine.retrieve("contact_Alice")!!.value)
        assertEquals("coffee then email", engine.retrieve("routine_morning")!!.value)
        // rememberContact should also store a graph relation user → knows → Alice
        assertEquals(1, engine.searchGraph("Alice").relations.size)
    }

    @Test
    fun retrieve_miss_returnsNull() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        assertNull(engine.retrieve("does-not-exist"))
    }

    @Test
    fun retrieveById_roundTrip() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        val mem = Memory(key = "byId", value = "v1")
        engine.store(mem)
        val found = engine.retrieveById(mem.id)
        assertNotNull(found)
        assertEquals("byId", found!!.key)
    }

    @Test
    fun update_modifiesValue() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        val mem = Memory(key = "note", value = "before")
        engine.store(mem)
        assertTrue(engine.update(mem.copy(value = "after")))
        assertEquals("after", engine.retrieve("note")!!.value)
    }

    @Test
    fun delete_removesById() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        val mem = Memory(key = "temp", value = "v")
        engine.store(mem)
        assertTrue(engine.delete(mem.id))
        assertNull(engine.retrieveById(mem.id))
    }

    @Test
    fun deleteByKey_removesMemoryAndGraphSubject() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        val mem = Memory(key = "contact_bob", value = "bob",
            relationships = listOf(Relation(subject = "contact_bob", predicate = "has", obj = "number")))
        engine.store(mem)
        assertTrue(engine.deleteByKey("contact_bob"))
        assertNull(engine.retrieve("contact_bob"))
        assertTrue(engine.searchGraph("contact_bob").relations.isEmpty())
    }

    @Test
    fun forget_isAliasForDeleteByKey() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        engine.remember("old", "value")
        assertTrue(engine.forget("old"))
        assertNull(engine.retrieve("old"))
    }

    // ── Search ────────────────────────────────────────────────────

    @Test
    fun searchByKeyword_findsMatchingValues() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        engine.remember("a", "the quick brown fox")
        engine.remember("b", "a lazy dog")
        engine.remember("c", "nothing in common")
        val hits = engine.searchByKeyword("fox")
        assertEquals(1, hits.size)
        assertEquals("a", hits[0].key)
    }

    @Test
    fun searchByCategory_filters() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        engine.remember("pref1", "dark", MemoryCategory.USER_PREFERENCE)
        engine.remember("pref2", "loud", MemoryCategory.USER_PREFERENCE)
        engine.remember("fact", "learned", MemoryCategory.LEARNED_FACT)
        val prefs = engine.searchByCategory(MemoryCategory.USER_PREFERENCE)
        assertEquals(2, prefs.size)
        val facts = engine.searchByCategory(MemoryCategory.LEARNED_FACT)
        assertEquals(1, facts.size)
    }

    @Test
    fun searchByTimeRange_filtersByCreatedAt() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        val now = System.currentTimeMillis()
        engine.store(Memory(key = "recent", value = "v", createdAt = now - 1_000))
        engine.store(Memory(key = "ancient", value = "v", createdAt = now - 40L * 86400000L))
        val hits = engine.searchByTimeRange(now - 60_000, now)
        assertEquals(1, hits.size)
        assertEquals("recent", hits[0].key)
    }

    @Test
    fun searchSemantic_returnsEmptyWithoutEngine() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        engine.remember("x", "hello world")
        val result = engine.searchSemantic("hello")
        assertTrue(result.memories.isEmpty())
        assertEquals(RetrievalType.SEMANTIC, result.retrievalType)
        assertEquals("vector", result.source)
    }

    @Test
    fun searchGraph_returnsRelatedRelations() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        engine.remember("crypto", "bitcoin",
            relations = listOf(
                Relation(subject = "crypto", predicate = "uses", obj = "blockchain"),
                Relation(subject = "crypto", predicate = "volatile", obj = "yes")
            ))
        val result = engine.searchGraph("crypto")
        assertEquals(RetrievalType.GRAPH, result.retrievalType)
        assertEquals(2, result.relations.size)
    }

    @Test
    fun searchHybrid_returnsHybridResult() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        engine.remember("z", "value")
        val result = engine.searchHybrid("value")
        assertEquals(RetrievalType.HYBRID, result.retrievalType)
        assertEquals("hybrid", result.source)
        assertNotNull(result)
    }

    // ── User control ──────────────────────────────────────────────

    @Test
    fun getAllMemories_returnsStored() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        engine.remember("one", "1")
        engine.remember("two", "2")
        engine.remember("three", "3")
        assertEquals(3, engine.getAllMemories().size)
        assertEquals(2, engine.getAllMemories(limit = 2).size)
    }

    @Test
    fun pin_unpin_pinnedList() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        val mem = Memory(key = "important", value = "v")
        engine.store(mem)
        assertTrue(engine.pinMemory(mem.id))
        assertEquals(1, engine.getPinnedMemories().size)
        assertTrue(engine.getPinnedMemories()[0].pinned)
        assertTrue(engine.unpinMemory(mem.id))
        assertTrue(engine.getPinnedMemories().isEmpty())
    }

    // ── Export / import ───────────────────────────────────────────

    @Test
    fun exportAll_containsChecksumAndData() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        engine.remember("e1", "v1")
        engine.remember("e2", "v2",
            relations = listOf(Relation(subject = "e2", predicate = "p", obj = "o")))
        val export = engine.exportAll()
        assertEquals(2, export.memories.size)
        assertEquals(1, export.relations.size)
        assertTrue(export.checksum.isNotEmpty())
        assertEquals(1, export.version)
    }

    @Test
    fun importExport_restoresMemoriesAndRelations() = runBlocking {
        val (engine1, _) = buildEngine()
        engine1.initialize()
        engine1.remember("imported", "value",
            relations = listOf(Relation(subject = "imported", predicate = "p", obj = "o")))
        val export = engine1.exportAll()

        val (engine2, _) = buildEngine()
        engine2.initialize()
        assertTrue(engine2.importExport(export))
        assertEquals(1, engine2.count())
        assertEquals("value", engine2.retrieve("imported")!!.value)
        assertEquals(1, engine2.searchGraph("imported").relations.size)
    }

    // ── Stats ─────────────────────────────────────────────────────

    @Test
    fun getStats_reportsCountsAndCategories() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        engine.remember("p1", "v", MemoryCategory.USER_PREFERENCE)
        engine.remember("p2", "v", MemoryCategory.USER_PREFERENCE)
        engine.remember("f1", "v", MemoryCategory.LEARNED_FACT)
        val stats = engine.getStats()
        assertEquals(3, stats.totalMemories)
        assertEquals(2, stats.byCategory[MemoryCategory.USER_PREFERENCE])
        assertEquals(1, stats.byCategory[MemoryCategory.LEARNED_FACT])
        assertTrue(stats.averageConfidence > 0f)
        assertTrue(stats.newest >= stats.oldest)
    }

    @Test
    fun count_matchesStoredRows() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        assertEquals(0, engine.count())
        engine.remember("c1", "v")
        engine.remember("c2", "v")
        assertEquals(2, engine.count())
    }

    @Test
    fun getLastConsolidationTime_returnsLifecycleValue() = runBlocking {
        val (engine, _) = buildEngine()
        engine.initialize()
        assertTrue(engine.getLastConsolidationTime() >= 0)
    }

    // ── Uninitialized guards ──────────────────────────────────────

    @Test
    fun uninitialized_operationsReturnDefaults() = runBlocking {
        val (engine, _) = buildEngine(encryptionInit = false)
        // Never call initialize(): every operation should short-circuit safely.
        assertFalse(engine.store(Memory(key = "x", value = "y")))
        assertNull(engine.retrieve("x"))
        assertNull(engine.retrieveById("x"))
        assertFalse(engine.delete("x"))
        assertFalse(engine.deleteByKey("x"))
        assertTrue(engine.searchByKeyword("q").isEmpty())
        assertTrue(engine.searchByCategory(MemoryCategory.TASK).isEmpty())
        assertTrue(engine.searchByTimeRange(0, 1).isEmpty())
        assertTrue(engine.getAllMemories().isEmpty())
        assertTrue(engine.getPinnedMemories().isEmpty())
        assertEquals(0, engine.getStats().totalMemories)
        assertTrue(engine.exportAll().memories.isEmpty())
    }
}

// ─────────────────────────────────────────────────────────────────────
//  VectorStore core logic (in-memory index, exercised via the seam)
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class VectorStoreCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun buildStore(): VectorStore {
        val settings = AiSettings(context)
        val profiler = DeviceProfiler(context)
        val inference = InferenceEngine(context, settings, profiler)
        return VectorStore(context, inference)
    }

    private fun mem(id: String, importance: Float = 0.5f) =
        Memory(id = id, key = id, value = "value of $id", importance = importance)

    @Test
    fun index_requiresReadyEngine_returnsFalse() = runBlocking {
        val store = buildStore()
        store.initialize(3)
        // Engine is not ready (no native model) → engine-backed index refuses.
        assertFalse(store.index(mem("m1")))
        assertTrue(store.indexWithEmbedding(mem("m2"), floatArrayOf(1f, 0f, 0f)))
    }

    @Test
    fun search_requiresReadyEngine_returnsEmpty() = runBlocking {
        val store = buildStore()
        store.initialize(3)
        assertTrue(store.search("query").isEmpty())
    }

    @Test
    fun indexWithEmbedding_normalizesAndIndexes() = runBlocking {
        val store = buildStore()
        store.initialize(3)
        assertTrue(store.indexWithEmbedding(mem("m1"), floatArrayOf(3f, 4f, 0f)))
        assertEquals(1, store.size())
        // [3,4,0] normalizes to [0.6,0.8,0]; same direction → similarity ~1
        val results = store.searchByEmbedding(floatArrayOf(3f, 4f, 0f))
        assertEquals(1, results.size)
        assertEquals("m1", results[0].memoryId)
        assertTrue(results[0].score > 0.99f)
    }

    @Test
    fun indexWithEmbedding_duplicate_skipsInsert() = runBlocking {
        val store = buildStore()
        store.initialize(3)
        assertTrue(store.indexWithEmbedding(mem("m1"), floatArrayOf(1f, 0f, 0f)))
        assertTrue(store.indexWithEmbedding(mem("m2"), floatArrayOf(0.99f, 0.02f, 0f)))
        // Second is a near-duplicate → kept as one entry
        assertEquals(1, store.size())
    }

    @Test
    fun indexWithEmbedding_emptyEmbedding_returnsFalse() = runBlocking {
        val store = buildStore()
        store.initialize(3)
        assertFalse(store.indexWithEmbedding(mem("m1"), FloatArray(0)))
        assertEquals(0, store.size())
    }

    @Test
    fun searchByEmbedding_ordersBySimilarity() = runBlocking {
        val store = buildStore()
        store.initialize(3)
        store.indexWithEmbedding(mem("x"), floatArrayOf(1f, 0f, 0f))
        store.indexWithEmbedding(mem("y"), floatArrayOf(0f, 1f, 0f))
        store.indexWithEmbedding(mem("z"), floatArrayOf(0f, 0f, 1f))
        val results = store.searchByEmbedding(floatArrayOf(1f, 0f, 0f), topK = 2)
        assertEquals(2, results.size)
        assertEquals("x", results[0].memoryId)
        assertTrue(results[0].score >= results[1].score)
    }

    @Test
    fun updateImportance_and_missingId() = runBlocking {
        val store = buildStore()
        store.initialize(3)
        store.indexWithEmbedding(mem("a"), floatArrayOf(1f, 0f, 0f))
        assertTrue(store.updateImportance("a", 0.9f))
        assertFalse(store.updateImportance("missing", 0.9f))
        val results = store.searchByEmbedding(floatArrayOf(1f, 0f, 0f))
        assertTrue(results[0].score > 0.99f) // unchanged vector still matches
    }

    @Test
    fun remove_removesEntry() = runBlocking {
        val store = buildStore()
        store.initialize(3)
        store.indexWithEmbedding(mem("a"), floatArrayOf(1f, 0f, 0f))
        store.indexWithEmbedding(mem("b"), floatArrayOf(0f, 1f, 0f))
        assertTrue(store.remove("a"))
        assertFalse(store.remove("a")) // already gone
        assertEquals(1, store.size())
    }

    @Test
    fun size_and_clear() = runBlocking {
        val store = buildStore()
        store.initialize(3)
        store.indexWithEmbedding(mem("a"), floatArrayOf(1f, 0f, 0f))
        store.indexWithEmbedding(mem("b"), floatArrayOf(0f, 1f, 0f))
        assertEquals(2, store.size())
        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun optimize_dropsLowestImportance() = runBlocking {
        val store = buildStore()
        store.initialize(2)
        // Angle-separated unit vectors: spacing 0.4 rad → cos(0.4) ≈ 0.92 < 0.95
        // duplicate threshold, so all 10 index uniquely.
        for (i in 0 until 10) {
            val angle = i * 0.4f
            store.indexWithEmbedding(mem("m$i", importance = i * 0.1f),
                floatArrayOf(kotlin.math.cos(angle), kotlin.math.sin(angle)))
        }
        assertEquals(10, store.size())
        val removed = store.optimize()
        // Keeps the top 90% by importance → drops exactly 1 of 10
        assertEquals(1, removed)
        assertEquals(9, store.size())
    }
}

// ─────────────────────────────────────────────────────────────────────
//  MemoryLifecycle (tiers, consolidation/forgetting decisions, cycle)
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class MemoryLifecycleCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun buildLifecycle(): MemoryLifecycle {
        context.deleteDatabase("openvoice_memories.db")
        context.deleteDatabase("openvoice_knowledge.db")
        val settings = AiSettings(context)
        val profiler = DeviceProfiler(context)
        val inference = InferenceEngine(context, settings, profiler)
        val vectorStore = VectorStore(context, inference)
        val graph = io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(context)
        val encryption = MemoryEncryption(context)
        return MemoryLifecycle(context, inference, encryption, vectorStore, graph)
    }

    @Test
    fun runCycle_firstCall_runsBothPhases() = runBlocking {
        val lifecycle = buildLifecycle()
        val report = lifecycle.runCycle()
        assertEquals(0, report.consolidated) // placeholders, but the cycle must run
        assertEquals(0, report.forgotten)
        assertEquals(0, report.totalMemories)
        assertTrue(report.cycleTimeMs >= 0)
        assertTrue(lifecycle.lastConsolidationTime > 0)
        assertTrue(lifecycle.lastForgettingTime > 0)
    }

    @Test
    fun runCycle_secondCall_skipsIntervals() = runBlocking {
        val lifecycle = buildLifecycle()
        lifecycle.runCycle()
        val report = lifecycle.runCycle() // intervals not elapsed → both skipped
        assertEquals(0, report.consolidated)
        assertEquals(0, report.forgotten)
    }

    @Test
    fun summarizeConversation_tooFewMemories_returnsNull() = runBlocking {
        val lifecycle = buildLifecycle()
        val summary = lifecycle.summarizeConversation(listOf(
            Memory(key = "a", value = "1")))
        assertNull(summary)
    }

    @Test
    fun summarizeConversation_engineNotReady_returnsNull() = runBlocking {
        val lifecycle = buildLifecycle()
        val summary = lifecycle.summarizeConversation((1..5).map {
            Memory(key = "k$it", value = "v$it")
        })
        assertNull(summary) // engine not ready (no native model)
    }

    @Test
    fun calculateImportance_combinesFactors() {
        val lifecycle = buildLifecycle()
        val fresh = Memory(key = "fresh", value = "v", createdAt = System.currentTimeMillis(),
            accessCount = 100, confidence = 1.0f)
        val stale = Memory(key = "stale", value = "v",
            createdAt = System.currentTimeMillis() - 100L * 86400000L,
            accessCount = 0, confidence = 0.1f)
        assertTrue(lifecycle.calculateImportance(fresh) > lifecycle.calculateImportance(stale))
        // Pinned memories get a bonus
        val pinned = fresh.copy(pinned = true)
        assertTrue(lifecycle.calculateImportance(pinned) > lifecycle.calculateImportance(fresh))
    }

    @Test
    fun shouldConsolidate_workingHighImportance() {
        val lifecycle = buildLifecycle()
        val working = Memory(key = "w", value = "v", category = MemoryCategory.WORKING,
            importance = 0.9f, confidence = 1.0f, accessCount = 50,
            createdAt = System.currentTimeMillis())
        assertTrue(lifecycle.shouldConsolidate(working))
        val learned = working.copy(category = MemoryCategory.LEARNED_FACT)
        assertFalse(lifecycle.shouldConsolidate(learned))
    }

    @Test
    fun shouldForget_cases() {
        val lifecycle = buildLifecycle()
        val now = System.currentTimeMillis()
        val expired = Memory(key = "e", value = "v",
            createdAt = now - 400L * 86400000L, ttlDays = 365, importance = 0.05f)
        assertTrue(lifecycle.shouldForget(expired))
        // Pinned memories are never forgotten
        assertFalse(lifecycle.shouldForget(expired.copy(pinned = true)))
        // Old + very low importance → forget even if unexpired
        val old = Memory(key = "o", value = "v",
            createdAt = now - 40L * 86400000L, ttlDays = 365, importance = 0.05f)
        assertTrue(lifecycle.shouldForget(old))
        // Healthy memory → keep
        val healthy = Memory(key = "h", value = "v",
            createdAt = now - 1000, ttlDays = 365, importance = 0.8f)
        assertFalse(lifecycle.shouldForget(healthy))
    }
}
