package io.github.crossbowcraft13.openvoice.memory

import java.util.UUID

/**
 * Memory category hierarchy.
 */
enum class MemoryCategory(val displayName: String, val ttlDays: Int) {
    USER_PREFERENCE("Preferences", 365),
    CONTACT("Contacts", 365),
    DEVICE("Devices", 365),
    LOCATION("Locations", 180),
    ROUTINE("Routines", 365),
    CONVERSATION("Conversations", 30),
    TASK("Tasks", 7),
    LEARNED_FACT("Learned Facts", 365),
    APP_USAGE("App Usage", 90),
    WORKING("Working Memory", 1),
    LONG_TERM("Long Term", 730)
}

/**
 * A single memory entry with full metadata.
 * Every memory includes:
 * - UUID (unique identifier)
 * - Timestamp (creation time)
 * - Source (which subsystem created it)
 * - Confidence (0.0–1.0)
 * - Category
 * - Embedding (for vector search)
 * - Graph relationships (for knowledge graph traversal)
 * - Expiration policy
 * - Encryption metadata
 */
data class Memory(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val accessedAt: Long = System.currentTimeMillis(),
    val source: String = "assistant",
    val confidence: Float = 1.0f,
    val category: MemoryCategory = MemoryCategory.LEARNED_FACT,
    val key: String,              // Primary key for lookup
    val value: String,            // The stored content
    val summary: String = "",     // Optional short description
    val embedding: FloatArray? = null,  // Vector for semantic search
    val ttlDays: Int = category.ttlDays, // Defaults to the category's TTL
    val importance: Float = 0.5f, // 0.0–1.0
    val accessCount: Int = 0,
    val pinned: Boolean = false,  // User-protected from forgetting
    val encrypted: Boolean = false,
    val relationships: List<Relation> = emptyList()  // Graph edges
) {
    val isExpired: Boolean get() =
        !pinned && System.currentTimeMillis() - createdAt > ttlDays * 86400000L

    val ageDays: Long get() = (System.currentTimeMillis() - createdAt) / 86400000L
}

/**
 * A relationship triple in the knowledge graph.
 * Subject → Predicate → Object
 * e.g., "Hunter" → "prefers" → "Spotify"
 */
data class Relation(
    val id: String = UUID.randomUUID().toString(),
    val subject: String,      // e.g., "Hunter"
    val predicate: String,    // e.g., "prefers", "uses", "owns"
    val obj: String,          // e.g., "Spotify"
    val confidence: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "assistant",
    val category: MemoryCategory = MemoryCategory.LEARNED_FACT
) {
    /** Invertible form: "subject predicate object" */
    val triple: String get() = "$subject | $predicate | $obj"
}

/**
 * Result of a memory retrieval operation.
 */
data class MemoryResult(
    val memories: List<Memory>,
    val relations: List<Relation> = emptyList(),
    val query: String = "",
    val latencyMs: Long = 0,
    val retrievalType: RetrievalType = RetrievalType.SEMANTIC,
    val source: String = "vector"
)

enum class RetrievalType {
    EXACT,       // Key-value lookup
    KEYWORD,     // Keyword match on value/summary
    SEMANTIC,    // Vector similarity
    GRAPH,       // Graph traversal
    HYBRID,      // Vector + Graph combined
    TIMERANGE,   // By time window
    CATEGORY     // By category
}

/**
 * Memory storage statistics.
 */
data class MemoryStats(
    val totalMemories: Int = 0,
    val totalRelations: Int = 0,
    val storageBytes: Long = 0,
    val byCategory: Map<MemoryCategory, Int> = emptyMap(),
    val oldest: Long = System.currentTimeMillis(),
    val newest: Long = System.currentTimeMillis(),
    val averageConfidence: Float = 0f,
    val lastConsolidation: Long = 0
)

/**
 * Export format for memory backups.
 * Users can export and import their memories.
 */
data class MemoryExport(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val memories: List<Memory>,
    val relations: List<Relation>,
    val checksum: String = ""
)
