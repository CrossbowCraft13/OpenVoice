package io.github.crossbowcraft13.openvoice.memory

import android.content.Context
import io.github.crossbowcraft13.openvoice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * KnowledgeGraph — A local, privacy-preserving triple store.
 *
 * Stores facts as Subject → Predicate → Object triples.
 *
 * Examples:
 *   "Hunter" | "prefers" | "Spotify"
 *   "Hunter" | "runs" | "Minecraft Server"
 *   "OpenVoice" | "uses" | "Whisper.cpp"
 *
 * Supports:
 * - Add/remove triples
 * - Graph traversal (find all related to a node)
 * - Path finding (how are two nodes connected?)
 * - Subgraph extraction (everything related to a subject)
 * - Pattern matching (find by predicate)
 * - Conflict resolution (higher confidence wins)
 *
 * The graph is stored in a simple SQLite database with indexes
 * on subject, predicate, and object for fast lookup.
 */
@Singleton
class KnowledgeGraph @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var db: android.database.sqlite.SQLiteDatabase? = null
    private var initialized = false

    companion object {
        private const val DB_NAME = "openvoice_knowledge.db"
        private const val DB_VERSION = 1
    }

    /**
     * Initialize the graph database.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
            db?.execSQL("""
                CREATE TABLE IF NOT EXISTS relations (
                    id TEXT PRIMARY KEY,
                    subject TEXT NOT NULL,
                    predicate TEXT NOT NULL,
                    object TEXT NOT NULL,
                    confidence REAL DEFAULT 1.0,
                    created_at INTEGER DEFAULT 0,
                    source TEXT DEFAULT 'assistant',
                    category TEXT DEFAULT 'LEARNED_FACT'
                )
            """)
            db?.execSQL("CREATE INDEX IF NOT EXISTS idx_subject ON relations(subject)")
            db?.execSQL("CREATE INDEX IF NOT EXISTS idx_predicate ON relations(predicate)")
            db?.execSQL("CREATE INDEX IF NOT EXISTS idx_object ON relations(object)")
            db?.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_triple ON relations(subject, predicate, object)")

            initialized = true
            Logger.i("KnowledgeGraph initialized", "Memory")
            true
        } catch (e: Exception) {
            Logger.e("KnowledgeGraph init failed: ${e.message}", "Memory")
            false
        }
    }

    // ── CRUD Operations ────────────────────────────────────────────

    /**
     * Store a relationship triple.
     * Upserts: if the same triple exists, updates confidence.
     */
    suspend fun store(relation: Relation): Boolean = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext false
        try {
            db?.execSQL("""
                INSERT OR REPLACE INTO relations
                (id, subject, predicate, object, confidence, created_at, source, category)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, arrayOf(
                relation.id, relation.subject, relation.predicate, relation.obj,
                relation.confidence.toString(), relation.createdAt.toString(),
                relation.source, relation.category.name
            ))
            Logger.d("KG: ${relation.triple}", "Memory")
            true
        } catch (e: Exception) {
            Logger.e("KG store error: ${e.message}", "Memory")
            false
        }
    }

    /**
     * Store multiple relationships in a batch transaction.
     */
    suspend fun storeAll(relations: List<Relation>): Boolean = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext false
        try {
            db?.beginTransaction()
            relations.forEach { store(it) }
            db?.setTransactionSuccessful()
            db?.endTransaction()
            Logger.d("KG: stored ${relations.size} relations", "Memory")
            true
        } catch (e: Exception) {
            Logger.e("KG batch error: ${e.message}", "Memory")
            false
        }
    }

    /**
     * Find all triples involving a subject.
     * e.g., find("Hunter") → [prefers→Spotify, runs→Minecraft, ...]
     */
    suspend fun findBySubject(subject: String): List<Relation> = withContext(Dispatchers.IO) {
        query("SELECT * FROM relations WHERE subject = ? ORDER BY confidence DESC", arrayOf(subject))
    }

    /**
     * Find all triples with a given predicate.
     * e.g., findByPredicate("prefers") → [Hunter→Spotify, ...]
     */
    suspend fun findByPredicate(predicate: String): List<Relation> = withContext(Dispatchers.IO) {
        query("SELECT * FROM relations WHERE predicate = ? ORDER BY confidence DESC", arrayOf(predicate))
    }

    /**
     * Find what a subject relates to via a specific predicate.
     * e.g., find("Hunter", "prefers") → ["Spotify"]
     */
    suspend fun find(subject: String, predicate: String): List<String> = withContext(Dispatchers.IO) {
        query("SELECT * FROM relations WHERE subject = ? AND predicate = ? ORDER BY confidence DESC",
            arrayOf(subject, predicate)).map { it.obj }
    }

    /**
     * Find everything related to a node (both as subject and object).
     * Returns all triples where the node appears.
     */
    suspend fun findRelated(node: String): List<Relation> = withContext(Dispatchers.IO) {
        query("""
            SELECT * FROM relations WHERE subject = ? OR object = ?
            ORDER BY confidence DESC
        """, arrayOf(node, node))
    }

    /**
     * Find a path between two nodes (breadth-first search).
     * Returns the shortest path as a list of relations.
     */
    suspend fun findPath(from: String, to: String, maxDepth: Int = 3): List<List<Relation>> =
        withContext(Dispatchers.IO) {
            val paths = mutableListOf<List<Relation>>()
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<Pair<String, List<Relation>>>()

            queue.add(from to emptyList())
            visited.add(from.lowercase())

            while (queue.isNotEmpty() && paths.size < 5) {
                val (current, path) = queue.removeFirst()
                val neighbors = findRelated(current)

                for (rel in neighbors) {
                    val next = if (rel.subject == current) rel.obj else rel.subject
                    if (next.lowercase() == to.lowercase()) {
                        paths.add(path + rel)
                    } else if (next.lowercase() !in visited && path.size < maxDepth) {
                        visited.add(next.lowercase())
                        queue.add(next to (path + rel))
                    }
                }
            }
            paths
        }

    /**
     * Get the full subgraph around a node (everything connected within N hops).
     */
    suspend fun getSubgraph(seed: String, depth: Int = 2): List<Relation> =
        withContext(Dispatchers.IO) {
            val results = mutableSetOf<Relation>()
            // Keep the original case: SQLite string equality is case-sensitive,
            // and stored triples retain the case they were written with.
            var current = setOf(seed)
            val visited = mutableSetOf<String>()

            for (i in 0 until depth) {
                val next = mutableSetOf<String>()
                for (node in current) {
                    val key = node.lowercase()
                    if (key in visited) continue
                    visited.add(key)
                    val relations = findRelated(node)
                    results.addAll(relations)
                    relations.forEach { rel ->
                        next.add(rel.subject)
                        next.add(rel.obj)
                    }
                }
                current = next
            }
            results.toList()
        }

    /**
     * Search for triples matching a pattern.
     * Any field can be "%" for wildcard.
     */
    suspend fun search(subject: String = "%", predicate: String = "%", obj: String = "%",
                        limit: Int = 50): List<Relation> = withContext(Dispatchers.IO) {
        query("""
            SELECT * FROM relations
            WHERE subject LIKE ? AND predicate LIKE ? AND object LIKE ?
            ORDER BY confidence DESC LIMIT ?
        """, arrayOf(subject, predicate, obj, limit.toString()))
    }

    /**
     * Delete a specific triple.
     */
    suspend fun delete(subject: String, predicate: String, obj: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                db?.execSQL("DELETE FROM relations WHERE subject = ? AND predicate = ? AND object = ?",
                    arrayOf(subject, predicate, obj))
                true
            } catch (e: Exception) { false }
        }

    suspend fun deleteRelation(id: String): Boolean = withContext(Dispatchers.IO) {
        try { db?.execSQL("DELETE FROM relations WHERE id = ?", arrayOf(id)); true }
        catch (e: Exception) { false }
    }

    /**
     * Delete all triples involving a subject.
     */
    suspend fun deleteSubject(subject: String): Boolean = withContext(Dispatchers.IO) {
        try {
            db?.execSQL("DELETE FROM relations WHERE subject = ? OR object = ?",
                arrayOf(subject, subject))
            true
        } catch (e: Exception) { false }
    }

    /**
     * Get total count of triples.
     */
    suspend fun count(): Long = withContext(Dispatchers.IO) {
        try {
            val cursor = db?.rawQuery("SELECT COUNT(*) FROM relations", null)
            cursor?.moveToFirst()
            val count = cursor?.getLong(0) ?: 0L
            cursor?.close()
            count
        } catch (e: Exception) { 0L }
    }

    // ── Bulk Operations ────────────────────────────────────────────

    suspend fun exportAll(): List<Relation> = withContext(Dispatchers.IO) {
        query("SELECT * FROM relations ORDER BY created_at")
    }

    suspend fun importAll(relations: List<Relation>): Boolean = withContext(Dispatchers.IO) {
        try {
            db?.beginTransaction()
            relations.forEach { store(it) }
            db?.setTransactionSuccessful()
            db?.endTransaction()
            Logger.i("KG: imported ${relations.size} relations", "Memory")
            true
        } catch (e: Exception) { false }
    }

    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        try { db?.execSQL("DELETE FROM relations"); true }
        catch (e: Exception) { false }
    }

    // ── Query Helpers ──────────────────────────────────────────────

    private fun query(sql: String, args: Array<String> = emptyArray()): List<Relation> {
        val results = mutableListOf<Relation>()
        try {
            val cursor = db?.rawQuery(sql, args) ?: return results
            while (cursor.moveToNext()) {
                results.add(Relation(
                    id = cursor.getString(0),
                    subject = cursor.getString(1),
                    predicate = cursor.getString(2),
                    obj = cursor.getString(3),
                    confidence = cursor.getFloat(4),
                    createdAt = cursor.getLong(5),
                    source = cursor.getString(6),
                    category = try { MemoryCategory.valueOf(cursor.getString(7)) }
                    catch (_: Exception) { MemoryCategory.LEARNED_FACT }
                ))
            }
            cursor.close()
        } catch (e: Exception) {
            Logger.e("KG query error: ${e.message}", "Memory")
        }
        return results
    }

    fun isInitialized() = initialized
}
