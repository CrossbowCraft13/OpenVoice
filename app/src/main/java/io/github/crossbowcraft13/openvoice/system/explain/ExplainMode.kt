package io.github.crossbowcraft13.openvoice.system.explain

import io.github.crossbowcraft13.openvoice.perception.PerceptionEngine
import io.github.crossbowcraft13.openvoice.perception.PerceptionSource
import io.github.crossbowcraft13.openvoice.planner.ExecutionPlan
import io.github.crossbowcraft13.openvoice.planner.ExecutionResult
import io.github.crossbowcraft13.openvoice.planner.PlanStep
import io.github.crossbowcraft13.openvoice.memory.MemoryEngine
import io.github.crossbowcraft13.openvoice.task.TaskBlackboard
import io.github.crossbowcraft13.openvoice.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ExplainMode — Complete transparency into why the assistant made every decision.
 *
 * When asked "why did you do that?", OpenVoice responds with:
 *   Goal: Connect Bluetooth headphones
 *   Planner selected: Accessibility
 *   Reason: Native Android APIs cannot pair arbitrary Bluetooth devices.
 *   Confidence: 96%
 *   Alternative: Vision was available but not used (A11y tree was sufficient).
 *
 * This builds user trust and makes debugging easy during development.
 */
@Singleton
class ExplainMode @Inject constructor(
    private val blackboard: TaskBlackboard,
    private val memoryEngine: MemoryEngine
) {
    private var lastPlan: ExecutionPlan? = null
    private var lastResult: ExecutionResult? = null
    private val decisionLog = mutableListOf<DecisionRecord>()

    data class DecisionRecord(
        val timestamp: Long = System.currentTimeMillis(),
        val goal: String,
        val capabilitySelected: String,
        val reason: String,
        val confidence: Float,
        val alternatives: List<String> = emptyList(),
        val cost: Float = 0f,
        val latencyMs: Long = 0,
        val success: Boolean = true
    )

    data class Explanation(
        val goal: String = "",
        val steps: List<ExplanationStep> = emptyList(),
        val capabilitiesUsed: List<String> = emptyList(),
        val capabilitiesNotUsed: List<String> = emptyList(),
        val totalTimeMs: Long = 0,
        val overallConfidence: Float = 0f,
        val batteryImpact: String = "Low",
        val decisions: List<DecisionRecord> = emptyList(),
        val failures: List<String> = emptyList()
    )

    data class ExplanationStep(
        val description: String,
        val capability: String,
        val confidence: Float,
        val latencyMs: Long = 0,
        val why: String = "",
        val alternatives: List<String> = emptyList()
    )

    /**
     * Record a planner decision for later explanation.
     */
    fun recordDecision(step: PlanStep, reason: String, alternatives: List<String> = emptyList()) {
        decisionLog.add(DecisionRecord(
            goal = step.description,
            capabilitySelected = step::class.simpleName ?: "unknown",
            reason = reason,
            confidence = step.minConfidence,
            alternatives = alternatives,
            cost = step.estimatedCost
        ))
    }

    /**
     * Record the completed plan and result.
     */
    fun recordExecution(plan: ExecutionPlan, result: ExecutionResult) {
        lastPlan = plan
        lastResult = result
    }

    /**
     * Generate a full explanation of the last task execution.
     */
    fun explainLast(): Explanation {
        val plan = lastPlan
        val result = lastResult
        val state = blackboard.state.value

        val steps = plan?.steps?.map { step ->
            val why = whyCapability(step)
            val alternatives = whatWasConsidered(step)
            ExplanationStep(
                description = step.description,
                capability = step::class.simpleName ?: "Unknown",
                confidence = step.minConfidence,
                latencyMs = step.estimatedLatencyMs,
                why = why,
                alternatives = alternatives
            )
        } ?: emptyList()

        val capabilitiesUsed = steps.map { it.capability }.distinct()
        val allCapabilities = listOf("NativeAction", "AccessibilityAction", "MemoryRetrieval",
            "OcrAction", "LmReasoning", "VisionAction", "PluginAction")
        val notUsed = allCapabilities - capabilitiesUsed.toSet()

        val failures = result?.let {
            if (!it.success) listOf("Step ${it.failedStepIndex}: ${it.failureReason}")
            else emptyList()
        } ?: emptyList()

        return Explanation(
            goal = plan?.goal ?: state.userGoal,
            steps = steps,
            capabilitiesUsed = capabilitiesUsed,
            capabilitiesNotUsed = notUsed,
            totalTimeMs = result?.totalLatencyMs ?: state.totalElapsedMs,
            overallConfidence = plan?.overallConfidence ?: 0f,
            batteryImpact = estimateBatteryImpact(steps),
            decisions = decisionLog.takeLast(50),
            failures = failures
        )
    }

    /**
     * Explain in natural language why a specific decision was made.
     */
    fun explainDecision(question: String): String {
        val lower = question.lowercase()

        return when {
            lower.contains("why") && lower.contains("tap") ->
                explainAction("tap")
            lower.contains("why") && lower.contains("open") ->
                explainAction("open")
            lower.contains("why") && (lower.contains("llm") || lower.contains("ai") || lower.contains("reason")) ->
                explainCapabilityUse("LmReasoning", "LLM reasoning is needed when the request requires understanding, summarization, or generating new information that wasn't already on screen or in memory.")
            lower.contains("why") && lower.contains("vision") ->
                explainCapabilityUse("VisionAction", "Vision was selected because the Accessibility tree didn't contain enough information about the visual layout, icons, or screen content.")
            lower.contains("why") && lower.contains("memory") ->
                explainCapabilityUse("MemoryRetrieval", "Memory was checked first because it's the cheapest way to answer personal questions — no AI model needed.")
            lower.contains("why") && (lower.contains("a11y") || lower.contains("accessibility")) ->
                explainCapabilityUse("AccessibilityAction", "Accessibility was selected because it can interact with the UI directly, which is faster and more reliable than screen analysis.")
            lower.contains("not") && (lower.contains("vision") || lower.contains("ai") || lower.contains("llm")) ->
                explainWhyNotUsed()
            lower.contains("confidence") || lower.contains("sure") ->
                explainConfidence()
            lower.contains("what") && lower.contains("next") ->
                explainNextSteps()
            lower.contains("time") || lower.contains("how long") ->
                explainTiming()
            else -> formatFullExplanation(explainLast())
        }
    }

    private fun explainAction(action: String): String {
        val lastDecision = decisionLog.lastOrNull { it.goal.contains(action, ignoreCase = true) }
        return if (lastDecision != null) {
            "I ${action} because: ${lastDecision.reason}. " +
                "This had ${"%.0f".format(lastDecision.confidence * 100)}% confidence " +
                "and cost ${"%.0f".format(lastDecision.cost * 100)}% of a full AI inference."
        } else {
            "I don't have a record of that specific action. Try asking about the most recent task."
        }
    }

    private fun explainCapabilityUse(capability: String, defaultReason: String): String {
        val state = blackboard.state.value
        val resolution = state.resolution
        return if (resolution != null) {
            val why = when (capability) {
                "NativeAction" -> "Native Android APIs were used because this is a standard device operation."
                "AccessibilityAction" -> "Accessibility was selected because it's faster and more reliable than vision or AI for UI interaction."
                "MemoryRetrieval" -> "Memory was checked first because it's the cheapest way to answer — no AI model required."
                "OcrAction" -> "OCR was used because the text content wasn't fully available through the Accessibility tree."
                else -> defaultReason
            }
            "$why\nGoal: ${state.userGoal}"
        } else defaultReason
    }

    private fun explainWhyNotUsed(): String {
        val explanation = explainLast()
        if (explanation.capabilitiesNotUsed.isEmpty()) return "All available capabilities were considered."
        return "The following capabilities were available but not needed:\n" +
            explanation.capabilitiesNotUsed.joinToString("\n") { "  - $it" } +
            "\n\nThe planner always uses the cheapest capable subsystem first. " +
            "More expensive options are only used when cheaper ones cannot complete the task."
    }

    private fun explainConfidence(): String {
        val explanation = explainLast()
        return "Overall confidence: ${"%.0f".format(explanation.overallConfidence * 100)}%\n" +
            explanation.steps.joinToString("\n") {
                "  ${it.description}: ${"%.0f".format(it.confidence * 100)}% (${it.capability})"
            }
    }

    private fun explainNextSteps(): String {
        val state = blackboard.state.value
        return if (state.plan.isNotEmpty() && !state.planComplete) {
            val remaining = state.plan.drop(state.currentStepIndex)
            "Next steps:\n" + remaining.joinToString("\n") { "  $it" }
        } else {
            "No pending steps. The current task is complete."
        }
    }

    private fun explainTiming(): String {
        val explanation = explainLast()
        return buildString {
            appendLine("Total time: ${explanation.totalTimeMs}ms")
            for (step in explanation.steps) {
                appendLine("  ${step.description}: ${step.latencyMs}ms (${step.capability})")
            }
        }
    }

    private fun whyCapability(step: PlanStep): String = when (step) {
        is PlanStep.NativeAction -> "Standard Android intent — fastest path."
        is PlanStep.AccessibilityAction -> "UI interaction via accessibility — faster than vision."
        is PlanStep.MemoryRetrieval -> "Cheapest way to retrieve personal information."
        is PlanStep.OcrAction -> "Text reading — cheaper than full vision model."
        is PlanStep.LmReasoning -> "Local LLM processes the request without sending data to the cloud."
        is PlanStep.VisionAction -> "Vision model needed for visual content not in accessibility tree."
        is PlanStep.UserConfirmation -> "User input required for sensitive or ambiguous actions."
        is PlanStep.PluginAction -> "Third-party plugin capability."
        is PlanStep.Conditional -> "Decision point based on current state."
        is PlanStep.Loop -> "Repeated operation over multiple items."
        is PlanStep.Wait -> "Waiting for UI or condition to stabilize."
        is PlanStep.Retry -> "Previous attempt failed — retrying with adjusted parameters."
    }

    private fun whatWasConsidered(step: PlanStep): List<String> = when (step) {
        is PlanStep.NativeAction -> listOf("Accessibility UI interaction (more expensive, not needed)")
        is PlanStep.AccessibilityAction -> listOf("Vision analysis (would work but slower)",
            "Native intent (not available for this UI element)")
        is PlanStep.MemoryRetrieval -> listOf("LLM reasoning (more expensive, not needed)")
        is PlanStep.OcrAction -> listOf("Vision model (more expensive, not needed)")
        is PlanStep.LmReasoning -> listOf("Vision model (not applicable for text reasoning)")
        is PlanStep.VisionAction -> listOf("LLM reasoning (cannot see the screen)")
        else -> emptyList()
    }

    private fun estimateBatteryImpact(steps: List<ExplanationStep>): String {
        if (steps.isEmpty()) return "None"
        val heavySteps = steps.count { it.capability in listOf("LmReasoning", "VisionAction") }
        return when {
            heavySteps == 0 -> "Low"
            heavySteps <= 1 -> "Medium"
            else -> "Higher"
        }
    }

    private fun formatFullExplanation(explanation: Explanation): String = buildString {
        appendLine("🎯 Goal: ${explanation.goal}")
        appendLine()

        for ((i, step) in explanation.steps.withIndex()) {
            appendLine("  ${i + 1}. ${step.description}")
            appendLine("     → ${step.capability} (${"%.0f".format(step.confidence * 100)}% confidence)")
            appendLine("     Why: ${step.why}")
            if (step.alternatives.isNotEmpty()) {
                appendLine("     Considered: ${step.alternatives.joinToString("; ")}")
            }
            appendLine()
        }

        if (explanation.capabilitiesNotUsed.isNotEmpty()) {
            appendLine("Available but not needed:")
            explanation.capabilitiesNotUsed.forEach { appendLine("  • $it") }
            appendLine()
        }

        appendLine("⏱ Total: ${explanation.totalTimeMs}ms")
        appendLine("🎯 Confidence: ${"%.0f".format(explanation.overallConfidence * 100)}%")
        appendLine("🔋 Battery impact: ${explanation.batteryImpact}")

        if (explanation.failures.isNotEmpty()) {
            appendLine("\n⚠ Issues encountered:")
            explanation.failures.forEach { appendLine("  • $it") }
        }
    }

    fun clearLog() {
        decisionLog.clear()
    }
}
