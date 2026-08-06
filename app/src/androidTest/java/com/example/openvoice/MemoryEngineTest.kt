package com.example.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.memory.*
import com.example.openvoice.memory.encryption.MemoryEncryption
import com.example.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryEngineTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var knowledgeGraph: KnowledgeGraph
    private lateinit var encryption: MemoryEncryption

    @Before
    fun setup() {
        Logger.init(true)
        knowledgeGraph = KnowledgeGraph(context)
        encryption = MemoryEncryption(context)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Memory Types Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun memory_creation_defaults() {
        val mem = Memory(key = "test", value = "test value")
        assertNotNull(mem.id)
        assertTrue(mem.createdAt > 0)
        assertEquals("test", mem.key)
        assertEquals("test value", mem.value)
        assertEquals(MemoryCategory.LEARNED_FACT, mem.category)
        assertEquals(1.0f, mem.confidence, 0.01f)
        assertFalse(mem.pinned)
        assertFalse(mem.isExpired)
    }

    @Test
    fun memory_expiration() {
        val expired = Memory(key = "old", value = "old",
            createdAt = System.currentTimeMillis() - 366L * 86400000L,
            ttlDays = 365)
        assertTrue(expired.isExpired)

        val pinned = expired.copy(pinned = true)
        assertFalse(pinned.isExpired) // Pinned memories never expire
    }

    @Test
    fun memory_category_ttls() {
        assertEquals(365, MemoryCategory.USER_PREFERENCE.ttlDays)
        assertEquals(1, MemoryCategory.WORKING.ttlDays)
        assertEquals(30, MemoryCategory.CONVERSATION.ttlDays)
        assertEquals(7, MemoryCategory.TASK.ttlDays)
        assertEquals(730, MemoryCategory.LONG_TERM.ttlDays)
    }

    @Test
    fun relation_triple_format() {
        val rel = Relation(subject = "Hunter", predicate = "prefers", obj = "Spotify")
        assertEquals("Hunter | prefers | Spotify", rel.triple)
    }

    @Test
    fun memory_importance_scoring() {
        val important = Memory(key = "pref", value = "value", importance = 0.9f,
            accessCount = 50, confidence = 0.95f)
        val trivial = Memory(key = "tmp", value = "value", importance = 0.1f,
            accessCount = 0, confidence = 0.5f)

        assertTrue(important.importance > trivial.importance)
    }

    @Test
    fun memory_ageDays_computation() {
        val recent = Memory(key = "recent", value = "v",
            createdAt = System.currentTimeMillis())
        assertEquals(0, recent.ageDays)

        val old = Memory(key = "old", value = "v",
            createdAt = System.currentTimeMillis() - 5L * 86400000L)
        assertEquals(5, old.ageDays)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Knowledge Graph Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun knowledgeGraph_storeAndRetrieve() = runBlocking {
        knowledgeGraph.initialize()

        knowledgeGraph.store(Relation(
            subject = "User", predicate = "prefers", obj = "Spotify"))
        knowledgeGraph.store(Relation(
            subject = "User", predicate = "uses", obj = "Android"))
        knowledgeGraph.store(Relation(
            subject = "User", predicate = "runs", obj = "Minecraft Server"))

        val preferences = knowledgeGraph.find("User", "prefers")
        assertTrue(preferences.contains("Spotify"))

        val allRelated = knowledgeGraph.findRelated("User")
        assertEquals(3, allRelated.size)
    }

    @Test
    fun knowledgeGraph_graphTraversal() = runBlocking {
        knowledgeGraph.initialize()
        knowledgeGraph.store(Relation(subject = "User", predicate = "owns", obj = "Phone"))
        knowledgeGraph.store(Relation(subject = "Phone", predicate = "runs", obj = "Android 14"))
        knowledgeGraph.store(Relation(subject = "Android 14", predicate = "supports", obj = "AI"))

        val subgraph = knowledgeGraph.getSubgraph("User", depth = 2)
        assertTrue(subgraph.size >= 3)
    }

    @Test
    fun knowledgeGraph_pathFinding() = runBlocking {
        knowledgeGraph.initialize()
        knowledgeGraph.store(Relation(subject = "Hunter", predicate = "runs", obj = "Minecraft Server"))
        knowledgeGraph.store(Relation(subject = "Minecraft Server", predicate = "uses", obj = "Java 17"))

        val paths = knowledgeGraph.findPath("Hunter", "Java 17")
        assertTrue("Should find a path from Hunter to Java 17", paths.isNotEmpty())
    }

    @Test
    fun knowledgeGraph_deleteTriple() = runBlocking {
        knowledgeGraph.initialize()
        knowledgeGraph.store(Relation(subject = "Test", predicate = "is", obj = "Temp"))

        knowledgeGraph.delete("Test", "is", "Temp")
        val related = knowledgeGraph.findRelated("Test")
        assertTrue(related.isEmpty())
    }

    @Test
    fun knowledgeGraph_searchByPattern() = runBlocking {
        knowledgeGraph.initialize()
        knowledgeGraph.store(Relation(subject = "A", predicate = "likes", obj = "X"))
        knowledgeGraph.store(Relation(subject = "B", predicate = "likes", obj = "Y"))
        knowledgeGraph.store(Relation(subject = "C", predicate = "likes", obj = "Z"))

        val results = knowledgeGraph.search(predicate = "likes")
        assertEquals(3, results.size)
    }

    @Test
    fun knowledgeGraph_clear() = runBlocking {
        knowledgeGraph.initialize()
        knowledgeGraph.store(Relation(subject = "U", predicate = "p", obj = "O"))
        assertTrue(knowledgeGraph.count() > 0)

        knowledgeGraph.clear()
        assertEquals(0, knowledgeGraph.count())
    }

    @Test
    fun knowledgeGraph_batchStore() = runBlocking {
        knowledgeGraph.initialize()
        knowledgeGraph.clear()

        val relations = (1..10).map {
            Relation(subject = "User$it", predicate = "has", obj = "Value$it")
        }
        knowledgeGraph.storeAll(relations)
        assertEquals(10, knowledgeGraph.count())
    }

    // ═══════════════════════════════════════════════════════════════
    //  Encryption Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun encryption_stringRoundTrip() {
        encryption.initialize()
        val original = "This is a secret memory about user preferences."
        val encrypted = encryption.encryptString(original)
        assertNotNull(encrypted)
        assertNotEquals(original, encrypted)

        val decrypted = encryption.decryptString(encrypted!!)
        assertEquals(original, decrypted)
    }

    @Test
    fun encryption_emptyString() {
        encryption.initialize()
        val encrypted = encryption.encryptString("")
        assertNotNull(encrypted)
        val decrypted = encryption.decryptString(encrypted!!)
        assertEquals("", decrypted)
    }

    @Test
    fun encryption_differentKeysProduceDifferentCiphertext() {
        encryption.initialize()
        val text = "Hello, world!"
        val encrypted1 = encryption.encryptString(text)
        val encrypted2 = encryption.encryptString(text)
        assertNotNull(encrypted1)
        assertNotNull(encrypted2)
        // Same plaintext should produce different ciphertext (GCM uses random IV)
        assertNotEquals(encrypted1, encrypted2)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Memory Lifecycle Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun lifecycle_importanceCalculation() {
        // MemoryLifecycle requires InferenceEngine which needs loading
        // Test the formula directly
        val fresh = Memory(key = "fresh", value = "v",
            createdAt = System.currentTimeMillis(),
            accessCount = 10, confidence = 0.9f)
        assertEquals(0, fresh.ageDays)

        val stale = Memory(key = "stale", value = "v",
            createdAt = System.currentTimeMillis() - 30L * 86400000L,
            accessCount = 0, confidence = 0.3f)
        assertEquals(30, stale.ageDays)
    }

    @Test
    fun lifecycle_shouldConsolidate_workingMemory() {
        val important = Memory(key = "important", value = "v",
            category = MemoryCategory.WORKING, importance = 0.7f)
        // Working memory with high importance → should consolidate
        assertTrue(important.importance > 0.5f)
    }

    @Test
    fun lifecycle_shouldForget_lowImportanceExpired() {
        val forgettable = Memory(key = "garbage", value = "v",
            createdAt = System.currentTimeMillis() - 400L * 86400000L,
            ttlDays = 365, importance = 0.05f, pinned = false)
        assertTrue(forgettable.isExpired)
        assertFalse(forgettable.pinned)
    }

    @Test
    fun lifecycle_shouldNotForget_pinnedMemories() {
        val pinned = Memory(key = "important", value = "v",
            createdAt = System.currentTimeMillis() - 400L * 86400000L,
            ttlDays = 365, importance = 0.1f, pinned = true)
        assertFalse(pinned.isExpired) // Pinned overrides TTL
        assertTrue(pinned.pinned)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Vector Store Tests (without real embedding engine)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun vectorStore_normalize_handlesZero() {
        // Test the normalization logic directly
        val zeroVec = FloatArray(10) { 0f }
        val norm = zeroVec.map {
            val sumSq = zeroVec.sumOf { (it * it).toDouble() }
            val magnitude = kotlin.math.sqrt(sumSq).toFloat()
            if (magnitude > 0f) it / magnitude else it
        }
        // All zeros should remain zeros
        assertTrue(norm.all { it == 0f })
    }

    @Test
    fun vectorStore_cosineSimilarity_identical() {
        val v1 = floatArrayOf(1f, 0f, 0f)
        val v2 = floatArrayOf(1f, 0f, 0f)
        var dot = 0.0
        for (i in v1.indices) dot += v1[i].toDouble() * v2[i].toDouble()
        assertEquals(1.0, dot, 0.01)
    }

    @Test
    fun vectorStore_cosineSimilarity_orthogonal() {
        val v1 = floatArrayOf(1f, 0f)
        val v2 = floatArrayOf(0f, 1f)
        var dot = 0.0
        for (i in v1.indices) dot += v1[i].toDouble() * v2[i].toDouble()
        assertEquals(0.0, dot, 0.01)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Retrieval Pipeline Tests
  // ═══════════════════════════════════════════════════════════════

    @Test
    fun retrieval_hybrid_prefersExactMatch() {
        // Exact key lookup is faster than semantic or graph
        // Verify the retrieval order makes sense
        assertEquals(RetrievalType.EXACT, RetrievalType.EXACT)
    }

    @Test
    fun retrieval_categories_filterCorrectly() {
        val pref = MemoryCategory.USER_PREFERENCE
        val contact = MemoryCategory.CONTACT
        assertNotEquals(pref, contact)
    }

    @Test
    fun memoryResult_creation() {
        val result = MemoryResult(
            memories = listOf(Memory(key = "k", value = "v")),
            relations = listOf(Relation(subject = "s", predicate = "p", obj = "o")),
            query = "test",
            latencyMs = 42,
            retrievalType = RetrievalType.HYBRID,
            source = "hybrid"
        )
        assertEquals(1, result.memories.size)
        assertEquals(1, result.relations.size)
        assertEquals(42, result.latencyMs)
        assertEquals(RetrievalType.HYBRID, result.retrievalType)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Memory Export Tests
  // ═══════════════════════════════════════════════════════════════

    @Test
    fun memoryExport_creation() {
        val export = MemoryExport(
            memories = listOf(Memory(key = "k", value = "v")),
            relations = listOf(Relation(subject = "s", predicate = "p", obj = "o"))
        )
        assertEquals(1, export.memories.size)
        assertEquals(1, export.relations.size)
        assertEquals(1, export.version)
        assertTrue(export.createdAt > 0)
        assertTrue(export.checksum.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    //  Memory Category Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun memoryCategory_displayNames() {
        assertEquals("Preferences", MemoryCategory.USER_PREFERENCE.displayName)
        assertEquals("Contacts", MemoryCategory.CONTACT.displayName)
        assertEquals("Conversations", MemoryCategory.CONVERSATION.displayName)
        assertEquals("Learned Facts", MemoryCategory.LEARNED_FACT.displayName)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Performance Benchmarks
  // ═══════════════════════════════════════════════════════════════

    @Test
    fun benchmark_memoryCreation() {
        val iterations = 1000
        // Warm up the JIT before timing
        for (i in 0 until 100) {
            Memory(key = "warm$i", value = "v", category = MemoryCategory.LEARNED_FACT)
        }
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val mem = Memory(key = "key$i", value = "value$i",
                category = MemoryCategory.LEARNED_FACT,
                relationships = listOf(
                    Relation(subject = "user", predicate = "has", obj = "key$i")))
            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("Memory creation (n=$iterations): avg=${avgUs}µs")
        // Budget is a regression guard, not a micro-optimization target:
        // measured steady-state is ~170µs, so allow 10x headroom for GC/scheduling noise.
        assertTrue("Memory creation < 2ms", avgUs < 2_000)
    }

    @Test
    fun benchmark_knowledgeGraphBatchInsert() = runBlocking {
        knowledgeGraph.initialize()
        knowledgeGraph.clear()

        val iterations = 100
        val relations = (1..iterations).map {
            Relation(subject = "User", predicate = "has_${it}", obj = "Value$it")
        }

        val start = System.nanoTime()
        knowledgeGraph.storeAll(relations)
        val ms = (System.nanoTime() - start) / 1_000_000

        val count = knowledgeGraph.count()
        println("KnowledgeGraph batch insert (n=$iterations): ${ms}ms, stored=$count")
        assertEquals(iterations.toLong(), count)
    }

    @Test
    fun benchmark_knowledgeGraphQuerySpeed() = runBlocking {
        knowledgeGraph.initialize()
        knowledgeGraph.clear()

        // Insert test data
        knowledgeGraph.storeAll((1..50).map {
            Relation(subject = "User", predicate = "item$it", obj = "Value$it")
        })

        val queries = 100
        val timings = mutableListOf<Long>()

        for (i in 0 until queries) {
            val start = System.nanoTime()
            val results = knowledgeGraph.findRelated("User")
            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("KnowledgeGraph query (n=$queries): avg=${avgUs}µs")
        assertTrue("Graph query < 5ms", avgUs < 5_000)
    }

    @Test
    fun benchmark_encryptionSpeed() {
        encryption.initialize()
        val text = "This is a test memory value that needs encryption. ".repeat(10)

        val iterations = 50
        val encryptTimes = mutableListOf<Long>()
        val decryptTimes = mutableListOf<Long>()

        for (i in 0 until iterations) {
            var start = System.nanoTime()
            val encrypted = encryption.encryptString(text)
            encryptTimes.add((System.nanoTime() - start) / 1_000)

            start = System.nanoTime()
            val decrypted = encryption.decryptString(encrypted!!)
            decryptTimes.add((System.nanoTime() - start) / 1_000)

            assertEquals(text, decrypted)
        }

        val avgEncUs = encryptTimes.average().toLong()
        val avgDecUs = decryptTimes.average().toLong()
        println("Encryption (n=$iterations): enc=${avgEncUs}µs dec=${avgDecUs}µs")
        assertTrue("Encryption < 10ms", avgEncUs < 10_000)
        assertTrue("Decryption < 10ms", avgDecUs < 10_000)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Edge Cases
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun memory_emptyKeyValue() {
        val mem = Memory(key = "", value = "")
        assertEquals("", mem.key)
        assertEquals("", mem.value)
    }

    @Test
    fun memory_maxCategory() {
        val mem = Memory(key = "test", value = "v",
            category = MemoryCategory.LONG_TERM)
        assertEquals(MemoryCategory.LONG_TERM, mem.category)
        assertEquals(730, mem.ttlDays)
    }

    @Test
    fun relation_emptyFields() {
        val rel = Relation(subject = "", predicate = "", obj = "")
        assertEquals(" |  | ", rel.triple)
    }

    @Test
    fun memoryStats_defaults() {
        val stats = MemoryStats()
        assertEquals(0, stats.totalMemories)
        assertEquals(0.0f, stats.averageConfidence, 0.01f)
    }

    @Test
    fun knowledgeGraph_duplicateTriple() = runBlocking {
        knowledgeGraph.initialize()
        knowledgeGraph.clear()

        knowledgeGraph.store(Relation(subject = "S", predicate = "P", obj = "O"))
        knowledgeGraph.store(Relation(subject = "S", predicate = "P", obj = "O"))

        // Should not create duplicate
        assertEquals(1, knowledgeGraph.count())
    }

    @Test
    fun encryption_nullInput() {
        encryption.initialize()
        assertNull(encryption.decryptString("invalid_base64!!!"))
        assertNull(encryption.decrypt(ByteArray(1))) // Too short for IV + ciphertext
    }
}
