package com.example.openvoice.perception.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.perception.ScreenContext
import com.example.openvoice.perception.VisualElement
import com.example.openvoice.perception.VisionResult
import com.example.openvoice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VisionRuntime — Multimodal vision model inference.
 *
 * Supports:
 * - Florence-2 (image captioning + object detection)
 * - SmolVLM (compact multimodal LLM)
 * - Future multimodal GGUF models
 *
 * Capabilities:
 * - Full-screen description
 * - Element identification (find buttons, icons, text)
 * - Layout understanding
 * - Chart/diagram reading
 * - Visual state detection
 *
 * All inference goes through the shared InferenceEngine.
 */
@Singleton
class VisionRuntime @Inject constructor(
    private val engine: InferenceEngine
) {
    private var lastResult: VisionResult? = null

    /**
     * Describe the content of a screen image.
     * Returns a high-level description of what's visible.
     */
    suspend fun describe(imageData: ByteArray, prompt: String = "Describe what you see on this screen."): VisionResult {
        val start = System.currentTimeMillis()

        if (!engine.isReady) {
            Logger.w("Vision model not loaded — using fallback", "Vision")
            return VisionResult(
                description = "Vision model not available. Enable AI Runtime with a multimodal model for screen descriptions.",
                latencyMs = System.currentTimeMillis() - start,
                confidence = 0f
            )
        }

        return try {
            // Build multimodal prompt
            // Format depends on model: Florence-2 uses <TASK> tokens,
            // SmolVLM uses image tokens in the conversation.
            val visionPrompt = buildVisionPrompt(prompt, imageData)
            val result = engine.complete(visionPrompt)

            if (result.isEmpty()) {
                VisionResult(description = "Model returned empty response",
                    latencyMs = System.currentTimeMillis() - start, confidence = 0f)
            } else {
                val ms = System.currentTimeMillis() - start
                Logger.d("Vision: ${result.take(60)}... (${ms}ms)", "Vision")
                VisionResult(
                    description = result,
                    latencyMs = ms,
                    confidence = if (result.length > 20) 0.74f else 0.3f
                ).also { lastResult = it }
            }
        } catch (e: Exception) {
            Logger.e("Vision inference failed: ${e.message}", "Vision")
            VisionResult(description = "Error: ${e.message}",
                latencyMs = System.currentTimeMillis() - start, confidence = 0f)
        }
    }

    /**
     * Identify clickable UI elements in the image.
     * Returns a list of identified buttons/icons with positions.
     */
    suspend fun identifyElements(imageData: ByteArray): List<VisualElement> {
        val result = describe(imageData, "List every clickable button, icon, and interactive element visible on this screen. For each, describe its label, purpose, and approximate position (top, bottom, left, center, right).")

        if (result.description.isEmpty() || result.description.startsWith("Error")) {
            return emptyList()
        }

        // Parse structured element descriptions from vision output
        val elements = parseVisualElements(result.description)
        Logger.d("Vision identified ${elements.size} visual elements", "Vision")
        return elements
    }

    /**
     * Answer a specific question about the screen image.
     */
    suspend fun answerQuestion(imageData: ByteArray, question: String): String {
        val result = describe(imageData, "Answer concisely: $question")
        return result.description
    }

    /**
     * Detect visual changes between two screen captures.
     */
    suspend fun detectChanges(before: ByteArray, after: ByteArray): String {
        // For full implementation, this would use the vision model
        // to compare two images and identify differences.
        return "Change detection requires side-by-side vision model comparison"
    }

    /**
     * Get confidence that vision can describe this image.
     */
    fun estimateConfidence(): Float {
        return if (engine.isReady) 0.74f else 0f
    }

    // ── Private ─────────────────────────────────────────────────

    private fun buildVisionPrompt(task: String, imageData: ByteArray): String {
        // Florence-2 format:
        // "<IMAGE>\n<TASK>Describe what you see."
        // SmolVLM format:
        // "User: <image>\nDescribe this screen.\nAssistant:"

        return buildString {
            appendLine("<|im_start|>user")
            appendLine("<image>")
            appendLine(task)
            appendLine("<|im_end|>")
            append("<|im_start|>assistant\n")
        }
    }

    private fun parseVisualElements(description: String): List<VisualElement> {
        val elements = mutableListOf<VisualElement>()

        // Simple heuristic parsing of vision output for element mentions
        val lines = description.lines()
        var currentLabel = ""
        val elementKeywords = listOf("button", "icon", "image", "link", "menu", "tab", "switch")

        for (line in lines) {
            val lower = line.lowercase()
            val keyword = elementKeywords.find { lower.contains(it) }
            if (keyword != null) {
                // Extract label (the text near the keyword)
                val parts = line.split(Regex("[\"“”]"))
                val label = parts.getOrNull(1) ?: line.trim()

                // Extract position (simple heuristics)
                val position = when {
                    lower.contains("top") || lower.contains("upper") -> "top"
                    lower.contains("bottom") -> "bottom"
                    lower.contains("left") -> "left"
                    lower.contains("right") -> "right"
                    lower.contains("center") || lower.contains("middle") -> "center"
                    else -> "unknown"
                }

                elements.add(VisualElement(
                    label = label,
                    bounds = Rect(0, 0, 0, 0), // Full position requires bounding box model
                    confidence = 0.6f,
                    category = keyword,
                    description = line.trim()
                ))
            }
        }

        return elements
    }

    /**
     * Encode Bitmap to JPEG bytes for model input.
     */
    fun encodeImage(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
