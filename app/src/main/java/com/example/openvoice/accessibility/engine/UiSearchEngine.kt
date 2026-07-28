package com.example.openvoice.accessibility.engine

import android.graphics.Rect
import com.example.openvoice.accessibility.SemanticUiNode
import com.example.openvoice.accessibility.UiRole
import com.example.openvoice.accessibility.ScreenState

/**
 * Query for locating UI elements.
 * Supports multiple search strategies that can be combined.
 */
data class UiQuery(
    val text: String? = null,
    val textExact: String? = null,
    val textContains: String? = null,
    val contentDescription: String? = null,
    val role: UiRole? = null,
    val roleName: String? = null,
    val isClickable: Boolean? = null,
    val isEditable: Boolean? = null,
    val isVisible: Boolean? = true,
    val isEnabled: Boolean? = true,
    val isScrollable: Boolean? = null,
    val isChecked: Boolean? = null,
    val maxResults: Int = 1,
    val nearCenterX: Int? = null,
    val nearCenterY: Int? = null,
    val aboveText: String? = null,
    val belowText: String? = null,
    val leftOfText: String? = null,
    val rightOfText: String? = null,
    val semanticSimilarTo: String? = null,
    val packageName: String? = null,
    val className: String? = null
)

/**
 * UI Search Engine — Locates UI elements by multiple strategies.
 *
 * Strategies (in order of preference):
 * 1. Exact text match (fastest, most reliable)
 * 2. Partial text / contains match
 * 3. Content description match
 * 4. Role + text combination
 * 5. Role only (e.g., "find any button")
 * 6. Relative position (above/below/left/right of another element)
 * 7. Coordinate proximity
 * 8. Semantic similarity (approximate match)
 */
class UiSearchEngine {

    data class SearchResult(
        val node: SemanticUiNode,
        val score: Float,        // 1.0 = perfect match, 0.0 = no match
        val matchStrategy: String
    )

    /**
     * Search the screen state for elements matching the given query.
     * Returns results sorted by confidence (highest first).
     */
    fun search(screen: ScreenState, query: UiQuery): List<SearchResult> {
        val candidates = mutableListOf<SearchResult>()
        val nodes = mutableListOf<SemanticUiNode>()

        // Flatten the tree
        fun flatten(n: SemanticUiNode) { nodes.add(n); n.children.forEach { flatten(it) } }
        screen.semanticTree?.let { flatten(it) }

        for (node in nodes) {
            val score = scoreMatch(node, query, nodes)
            if (score > 0f) {
                candidates.add(SearchResult(node, score, describeStrategy(query, node)))
            }
        }

        return candidates.sortedByDescending { it.score }.take(query.maxResults)
    }

    /**
     * Quick search: find first element matching text or role.
     */
    fun findFirst(screen: ScreenState, query: UiQuery): SemanticUiNode? =
        search(screen, query).firstOrNull()?.node

    /**
     * Find all elements matching criteria.
     */
    fun findAll(screen: ScreenState, query: UiQuery): List<SemanticUiNode> =
        search(screen, query.copy(maxResults = Int.MAX_VALUE)).map { it.node }

    /**
     * Find editable text fields on screen.
     */
    fun findTextFields(screen: ScreenState): List<SemanticUiNode> =
        findAll(screen, UiQuery(isEditable = true))

    /**
     * Find buttons labeled with specific text.
     */
    fun findButton(screen: ScreenState, text: String): SemanticUiNode? =
        findFirst(screen, UiQuery(textContains = text, role = UiRole.BUTTON, isClickable = true))
            ?: findFirst(screen, UiQuery(textContains = text, isClickable = true))

    /**
     * Score a single node against the query.
     * Returns 0.0 if no match, 1.0 if perfect match.
     */
    private fun scoreMatch(node: SemanticUiNode, query: UiQuery, allNodes: List<SemanticUiNode>): Float {
        var score = 0.5f // base

        // ── Visibility ─────────────────────────────────────────────
        if (query.isVisible != null && node.isVisible != query.isVisible) return 0f
        if (query.isEnabled != null && node.isEnabled != query.isEnabled) return 0f

        // ── Exact text match (perfect score) ───────────────────────
        if (query.textExact != null) {
            if (node.text?.equals(query.textExact, ignoreCase = true) == true ||
                node.contentDescription?.equals(query.textExact, ignoreCase = true) == true)
                score += 0.5f
            else return 0f
        }

        // ── Partial text match ─────────────────────────────────────
        if (query.text != null || query.textContains != null) {
            val search = (query.text ?: query.textContains)!!.lowercase()
            val nodeText = node.text?.lowercase() ?: ""
            val nodeDesc = node.contentDescription?.lowercase() ?: ""
            if (nodeText.contains(search) || nodeDesc.contains(search)) score += 0.3f
            else if (query.text != null) return 0f // exact text required but not found
        }

        // ── Content description ────────────────────────────────────
        query.contentDescription?.let { desc ->
            if (node.contentDescription?.equals(desc, ignoreCase = true) == true) score += 0.3f
            else return 0f
        }

        // ── Role match ─────────────────────────────────────────────
        query.role?.let { role ->
            if (node.role == role) score += 0.2f
            else return 0f
        }
        query.roleName?.let { name ->
            if (node.role.name.equals(name, ignoreCase = true) ||
                node.role == UiRole.fromClassName(name)) score += 0.2f
            else return 0f
        }

        // ── Clickable / Editable / Scrollable ──────────────────────
        query.isClickable?.let { if (node.isClickable != it) return 0f }
        query.isEditable?.let { if (node.isEditable != it) return 0f }
        query.isScrollable?.let { if (node.isScrollable != it) return 0f }
        query.isChecked?.let { if (node.isChecked != it) return 0f }

        // ── Package name ───────────────────────────────────────────
        query.packageName?.let { pkg ->
            if (node.packageName?.equals(pkg, ignoreCase = true) == true) score += 0.1f
            else return 0f
        }

        // ── Class name ─────────────────────────────────────────────
        query.className?.let { cn ->
            if (node.className?.contains(cn, ignoreCase = true) == true) score += 0.1f
            else return 0f
        }

        // ── Relative position ──────────────────────────────────────
        query.aboveText?.let { label ->
            val target = findLabelNode(label, allNodes) ?: return 0f
            if (node.bounds.bottom > target.bounds.top) score -= 0.2f
        }
        query.belowText?.let { label ->
            val target = findLabelNode(label, allNodes) ?: return 0f
            if (node.bounds.top < target.bounds.bottom) score -= 0.2f
        }
        query.leftOfText?.let { label ->
            val target = findLabelNode(label, allNodes) ?: return 0f
            if (node.bounds.right > target.bounds.left) score -= 0.2f
        }
        query.rightOfText?.let { label ->
            val target = findLabelNode(label, allNodes) ?: return 0f
            if (node.bounds.left < target.bounds.right) score -= 0.2f
        }

        // ── Coordinate proximity ───────────────────────────────────
        query.nearCenterX?.let { x ->
            query.nearCenterY?.let { y ->
                val dist = Math.sqrt(
                    ((node.centerX - x).toDouble().square() + (node.centerY - y).toDouble().square())
                )
                if (dist < 100) score += 0.2f
                else if (dist < 300) score += 0.1f
            }
        }

        // ── Semantic similarity (simple fallback) ─────────────────
        query.semanticSimilarTo?.let { target ->
            // Simple word overlap score
            val words = target.lowercase().split(Regex("\\s+")).toSet()
            val nodeWords = (node.text?.lowercase() ?: "").split(Regex("\\s+")).toSet()
            val overlap = words.intersect(nodeWords).size.toFloat() / words.size.coerceAtLeast(1)
            if (overlap < 0.3f) score -= 0.2f
            score += overlap * 0.2f
        }

        return score.coerceIn(0f, 1f)
    }

    private fun describeStrategy(query: UiQuery, node: SemanticUiNode): String = when {
        query.textExact != null -> "exact_text"
        query.text != null -> "text"
        query.textContains != null -> "text_contains"
        query.role != null -> "role"
        query.contentDescription != null -> "content_desc"
        query.nearCenterX != null -> "coordinates"
        else -> "compound"
    }

    private fun findLabelNode(label: String, nodes: List<SemanticUiNode>): SemanticUiNode? {
        return nodes.find {
            it.text?.equals(label, ignoreCase = true) == true ||
                it.contentDescription?.equals(label, ignoreCase = true) == true
        }
    }

    private fun Double.square() = this * this
    private fun Float.square() = this * this
    private fun Int.square() = this.toDouble() * this.toDouble()
}
