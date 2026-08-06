package com.example.openvoice.perception

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.openvoice.accessibility.SemanticUiNode
import com.example.openvoice.accessibility.ScreenState
import com.example.openvoice.accessibility.UiRole

/**
 * ScreenContext — THE unified representation of the current screen.
 *
 * Every subsystem (memory, planner, plugins, LLM) consumes ScreenContext.
 * No subsystem should read OCR, screenshots, or vision data directly.
 *
 * ScreenContext fuses:
 * 1. Accessibility UI Tree
 * 2. OCR text with bounding boxes
 * 3. Vision model observations
 * 4. Confidence scores from each source
 *
 * The PerceptionEngine chooses the most trustworthy source per element.
 */
data class ScreenContext(
    // ── Screen Identity ─────────────────────────────────────────
    val packageName: String = "",
    val activityName: String = "",
    val screenName: String = "",
    val captureTimestamp: Long = System.currentTimeMillis(),

    // ── Fused Interactive Elements ──────────────────────────────
    val elements: List<ContextElement> = emptyList(),

    // ── Raw Text Content (merged from A11y + OCR) ──────────────
    val textBlocks: List<TextBlock> = emptyList(),
    val fullText: String = "",

    // ── Visual-only Elements (not in Accessibility tree) ────────
    val visualElements: List<VisualElement> = emptyList(),

    // ── Screen State ────────────────────────────────────────────
    val hasDialogs: Boolean = false,
    val dialogText: List<String> = emptyList(),
    val keyboardVisible: Boolean = false,
    val scrollPosition: ScrollPosition = ScrollPosition(),

    // ── Source Confidence ───────────────────────────────────────
    val sourceConfidence: Map<PerceptionSource, Float> = emptyMap(),

    // ── Change Tracking ─────────────────────────────────────────
    val changedSinceLastCapture: Boolean = false,
    val changedRegions: List<Rect> = emptyList(),

    // ── Raw data (for debug / Developer Console) ────────────────
    val screenshot: Bitmap? = null,
    val rawA11yTree: SemanticUiNode? = null
) {
    /** Short description for Developer Console. */
    val summary: String get() = buildString {
        appendLine("📱 $packageName")
        if (activityName.isNotEmpty()) appendLine("   Activity: $activityName")
        appendLine("   ${elements.size} elements (a11y) + ${visualElements.size} (vision)")
        appendLine("   Text blocks: ${textBlocks.size}")
        appendLine("   Dialogs: ${if (hasDialogs) dialogText.joinToString(", ") else "none"}")
        appendLine("   Sources: ${sourceConfidence.entries.joinToString(", ") { "${it.key}=${"%.2f".format(it.value)}" }}")
    }

    /** Find interactive elements matching text. */
    fun findElements(text: String): List<ContextElement> =
        elements.filter { it.text?.contains(text, ignoreCase = true) == true }

    /** Find a clickable element by label. */
    fun findClickable(label: String): ContextElement? =
        elements.find { it.isClickable && it.bestLabel?.contains(label, ignoreCase = true) == true }

    /** Find editable text fields. */
    fun findTextFields(): List<ContextElement> =
        elements.filter { it.isEditable }

    /** Get all visible text on screen as paragraphs. */
    fun paragraphs(): List<String> =
        textBlocks.sortedBy { it.bounds.top }.map { it.text }
}

/**
 * A single interactive element on screen, fused from all available sources.
 */
data class ContextElement(
    val id: String = "",

    // ── Labels (from best available source) ──────────────────────
    val text: String? = null,          // From Accessibility node
    val ocrText: String? = null,       // From OCR
    val visionLabel: String? = null,   // From vision model
    val contentDescription: String? = null,

    // ── Role ─────────────────────────────────────────────────────
    val role: UiRole = UiRole.UNKNOWN,
    val roleName: String = "unknown",

    // ── Position ─────────────────────────────────────────────────
    val bounds: Rect = Rect(),
    val centerX: Int = 0,
    val centerY: Int = 0,

    // ── Interaction State ────────────────────────────────────────
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisible: Boolean = true,
    val isEditable: Boolean = false,
    val isChecked: Boolean? = null,
    val isCheckable: Boolean = false,
    val isScrollable: Boolean = false,
    val isFocusable: Boolean = false,

    // ── Source Attribution ───────────────────────────────────────
    val source: PerceptionSource = PerceptionSource.ACCESSIBILITY,
    val confidence: Float = 1.0f,

    // ── Metadata ─────────────────────────────────────────────────
    val packageName: String? = null,
    val viewId: String? = null
) {
    /** Best available label string. */
    val bestLabel: String?
        get() = text ?: ocrText ?: visionLabel ?: contentDescription ?: viewId

    companion object {
        fun fromSemanticNode(node: SemanticUiNode, source: PerceptionSource = PerceptionSource.ACCESSIBILITY,
                              confidence: Float = 0.99f): ContextElement = ContextElement(
            text = node.text,
            contentDescription = node.contentDescription,
            role = node.role,
            roleName = node.role.name,
            bounds = node.bounds,
            centerX = node.centerX,
            centerY = node.centerY,
            isClickable = node.isClickable,
            isEnabled = node.isEnabled,
            isVisible = node.isVisible,
            isEditable = node.isEditable,
            isChecked = node.isChecked,
            isCheckable = node.isCheckable,
            isScrollable = node.isScrollable,
            isFocusable = node.isFocusable,
            source = source,
            confidence = confidence,
            packageName = node.packageName,
            viewId = node.viewId
        )
    }
}

/**
 * A block of text detected by OCR, with position.
 */
data class TextBlock(
    val text: String,
    val bounds: Rect,
    val confidence: Float = 1.0f,
    val isFromA11y: Boolean = true,
    val paragraphIndex: Int = 0,
    val readingOrder: Int = 0
)

/**
 * A visual-only element detected by the vision model (not in accessibility tree).
 */
data class VisualElement(
    val label: String,
    val bounds: Rect,
    val confidence: Float,
    val category: String = "unknown",
    val description: String = ""
)

data class ScrollPosition(
    val x: Int = 0,
    val y: Int = 0,
    val maxX: Int = 0,
    val maxY: Int = 0,
    val visibleTop: Int = 0,
    val visibleBottom: Int = 0
)

enum class PerceptionSource(val displayName: String, val priority: Int) {
    ACCESSIBILITY("Accessibility", 1),  // Highest priority (fastest, most reliable)
    OCR("OCR", 2),
    VISION("Vision", 3),
    LLM("LLM", 4)                      // Lowest priority (expensive)
}

/** Result of an OCR operation. */
data class OcrResult(
    val textBlocks: List<TextBlock>,
    val fullText: String,
    val latencyMs: Long = 0,
    val confidence: Float = 0f
)

/** Result of a vision model operation. */
data class VisionResult(
    val description: String = "",
    val elements: List<VisualElement> = emptyList(),
    val labels: List<String> = emptyList(),
    val latencyMs: Long = 0,
    val confidence: Float = 0f
)
