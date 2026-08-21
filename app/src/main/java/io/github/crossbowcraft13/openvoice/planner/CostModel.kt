package io.github.crossbowcraft13.openvoice.planner

import io.github.crossbowcraft13.openvoice.accessibility.VoiceAccessibilityService
import io.github.crossbowcraft13.openvoice.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CostModel — Determines the cheapest capable subsystem for any given operation.
 *
 * This is the evolution of CapabilityRouter. Instead of routing single intents,
 * the Planner uses CostModel to select the optimal capability for each plan step
 * with full awareness of the overall task.
 *
 * Cost hierarchy (lower = cheaper, faster, preferred):
 *
 *   0.01  Wait, conditional checks
 *   0.05  User confirmation (fastest human-readable)
 *   0.10  Native Android API (intents, SMS, AudioManager)
 *   0.15  Memory retrieval (key-value, fast)
 *   0.20  Accessibility Service (UI interaction)
 *   0.30  OCR (text recognition)
 *   0.40  Plugin (third-party)
 *   0.50  Memory search (semantic/vector)
 *   0.60  Knowledge Graph (graph traversal)
 *   0.70  Local LLM (reasoning)
 *   0.80  Vision model (multimodal)
 *   0.90  Multi-step planner (meta-planning)
 */
@Singleton
class CostModel @Inject constructor() {

    fun costForNative(): Float = 0.10f
    fun costForAccessibility(): Float = 0.20f
    fun costForMemoryRetrieval(): Float = 0.15f
    fun costForOcr(): Float = 0.30f
    fun costForPlugin(): Float = 0.40f
    fun costForSemanticSearch(): Float = 0.50f
    fun costForGraphTraversal(): Float = 0.60f
    fun costForLlm(): Float = 0.70f
    fun costForVision(): Float = 0.80f
    fun costForPlanning(): Float = 0.90f

    /**
     * Select the cheapest capability that can satisfy a given description.
     * Uses the same priority as the original CapabilityRouter but now
     * the Planner makes the final decision.
     */
    fun selectCheapestCapability(
        description: String,
        requiresScreenInteraction: Boolean = false,
        requiresReasoning: Boolean = false,
        requiresVisualAnalysis: Boolean = false
    ): PlanStep {
        val lower = description.lowercase()
        val a11yAvailable = VoiceAccessibilityService.isRunning()

        // Tier 1: Native Android API (cheapest)
        val nativePatterns = listOf("open ", "launch ", "start ", "set ", "create ",
            "call ", "text ", "send ", "volume", "enable ", "disable ", "turn on", "turn off")
        if (nativePatterns.any { lower.startsWith(it) || lower.contains(it) }) {
            Logger.d("CostModel → Native: $description", "Planner")
            val operatorId = resolveOperatorId(lower)
            return PlanStep.NativeAction(
                description = description,
                operatorId = operatorId,
                estimatedCost = costForNative()
            )
        }

        // Tier 2: Accessibility (if service is running and screen interaction needed)
        if (a11yAvailable && (requiresScreenInteraction || isScreenAction(lower))) {
            Logger.d("CostModel → A11y: $description", "Planner")
            return PlanStep.AccessibilityAction(
                description = description,
                command = description,
                estimatedCost = costForAccessibility()
            )
        }

        // Tier 3: Memory retrieval (facts, preferences)
        if (isMemoryQuery(lower)) {
            Logger.d("CostModel → Memory: $description", "Planner")
            return PlanStep.MemoryRetrieval(
                description = description,
                query = description,
                estimatedCost = costForMemoryRetrieval()
            )
        }

        // Tier 4: OCR (text on screen)
        if (lower.contains("read") || lower.contains("text") || lower.contains("content")) {
            Logger.d("CostModel → OCR: $description", "Planner")
            return PlanStep.OcrAction(
                description = description,
                estimatedCost = costForOcr()
            )
        }

        // Tier 5: Local LLM (reasoning)
        if (requiresReasoning || lower.contains("summarize") || lower.contains("explain") ||
            lower.contains("what") || lower.contains("how") || lower.contains("why")) {
            Logger.d("CostModel → LLM: $description", "Planner")
            return PlanStep.LmReasoning(
                description = description,
                prompt = description,
                estimatedCost = costForLlm()
            )
        }

        // Tier 6: Vision (visual analysis)
        if (requiresVisualAnalysis || lower.contains("see") || lower.contains("look") ||
            lower.contains("describe") || lower.contains("chart") || lower.contains("icon")) {
            Logger.d("CostModel → Vision: $description", "Planner")
            return PlanStep.VisionAction(
                description = description,
                question = description,
                estimatedCost = costForVision()
            )
        }

        // Fallback: LLM reasoning
        Logger.d("CostModel → LLM (fallback): $description", "Planner")
        return PlanStep.LmReasoning(
            description = description,
            prompt = description,
            estimatedCost = costForLlm()
        )
    }

    /**
     * Check whether a lower-cost capability can handle a step before escalating.
     */
    fun canEscalateTo(currentCost: Float, targetCost: Float): Boolean {
        return targetCost > currentCost
    }

    /**
     * Get a suggested alternate capability for a failed step.
     * Escalates up the cost hierarchy.
     */
    fun suggestAlternate(step: PlanStep): PlanStep? {
        return when (step) {
            is PlanStep.NativeAction -> {
                if (VoiceAccessibilityService.isRunning())
                    PlanStep.AccessibilityAction(step.description, step.description,
                        estimatedCost = costForAccessibility())
                else null
            }
            is PlanStep.AccessibilityAction ->
                PlanStep.OcrAction(step.description, estimatedCost = costForOcr())
            is PlanStep.OcrAction ->
                PlanStep.VisionAction(step.description, estimatedCost = costForVision())
            is PlanStep.MemoryRetrieval ->
                PlanStep.LmReasoning("Search memory for: ${step.query}", step.query,
                    estimatedCost = costForLlm())
            is PlanStep.LmReasoning ->
                PlanStep.VisionAction(step.description, step.prompt,
                    estimatedCost = costForVision())
            is PlanStep.VisionAction -> null // No cheaper fallback for vision
            is PlanStep.UserConfirmation -> null
            is PlanStep.PluginAction -> null
            is PlanStep.Conditional -> null
            is PlanStep.Loop -> null
            is PlanStep.Wait -> null
            is PlanStep.Retry -> null
        }
    }

    // ── Private helpers ────────────────────────────────────────────

    private fun resolveOperatorId(text: String): String {
        return when {
            text.contains("open") || text.contains("launch") -> "LAUNCH_APP"
            text.contains("call") || text.contains("dial") -> "MAKE_CALL"
            text.contains("text") || text.contains("message") || text.contains("sms") -> "SEND_SMS"
            text.contains("timer") -> "SET_TIMER"
            text.contains("alarm") -> "SET_ALARM"
            text.contains("volume") -> "ADJUST_VOLUME"
            text.contains("setting") -> "OPEN_SETTINGS"
            text.contains("bluetooth") -> "OPEN_SETTINGS"
            text.contains("wifi") || text.contains("wi-fi") -> "OPEN_SETTINGS"
            text.contains("enable") || text.contains("disable") -> "ADJUST_VOLUME"
            else -> "LAUNCH_APP"
        }
    }

    private fun isScreenAction(text: String): Boolean {
        val screenPatterns = listOf("tap ", "click ", "press ", "scroll ", "type ",
            "select ", "choose ", "find ", "back", "home", "go to")
        return screenPatterns.any { text.startsWith(it) || text.contains(it) }
    }

    private fun isMemoryQuery(text: String): Boolean {
        val memoryPatterns = listOf("remember", "forget", "what is my", "what's my",
            "what are my", "do i", "my favorite", "my preferred", "my name",
            "who is", "where is", "when did")
        return memoryPatterns.any { text.contains(it) }
    }
}
