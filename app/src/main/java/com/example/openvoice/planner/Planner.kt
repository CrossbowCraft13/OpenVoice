package com.example.openvoice.planner

import com.example.openvoice.accessibility.VoiceAccessibilityService
import com.example.openvoice.intent.IntentClassifier
import com.example.openvoice.intent.IntentResult
import com.example.openvoice.memory.MemoryEngine
import com.example.openvoice.operator.OperatorRegistry
import com.example.openvoice.perception.PerceptionEngine
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Planner — Converts user goals into executable plans.
 *
 * This is the SINGLE orchestration layer for the entire assistant.
 *
 * Flow:
 *   1. Receive user request (from voice or text)
 *   2. Classify intent (via IntentClassifier)
 *   3. Decompose into executable steps
 *   4. Select cheapest capability for each step (via CostModel)
 *   5. Estimate confidence and duration
 *   6. Return ExecutionPlan
 *
 * The Planner owns the decision-making. The old CapabilityRouter's
 * routing logic now lives inside CostModel, called by the Planner.
 */
@Singleton
class Planner @Inject constructor(
    private val intentClassifier: IntentClassifier,
    private val costModel: CostModel,
    private val operatorRegistry: OperatorRegistry,
    private val perceptionEngine: PerceptionEngine,
    private val memoryEngine: MemoryEngine,
    private val blackboard: TaskBlackboard
) {

    suspend fun createPlan(userRequest: String): ExecutionPlan = withContext(Dispatchers.Default) {
        Logger.i("Planning: $userRequest", "Planner")
        val start = System.currentTimeMillis()

        // 1. Classify the request
        val intent = intentClassifier.classify(userRequest)
        blackboard.setIntent(intent, null)

        // 2. Decompose into steps
        val steps = decompose(userRequest, intent)

        // 3. Estimate totals
        val totalCost = steps.sumOf { it.estimatedCost.toDouble() }.toFloat()
        val totalLatency = steps.sumOf { it.estimatedLatencyMs.toLong() }
        val avgConfidence = steps.map { it.minConfidence }.average().toFloat().coerceIn(0f, 1f)

        val plan = ExecutionPlan(
            goal = userRequest,
            goalSummary = intent.intent,
            steps = steps,
            estimatedTotalCost = totalCost,
            estimatedTotalLatencyMs = totalLatency,
            overallConfidence = avgConfidence
        )

        blackboard.setPlan(plan.steps.map { it.description })

        val ms = System.currentTimeMillis() - start
        Logger.i("Plan created: ${steps.size} steps, ${"%.1f".format(avgConfidence * 100)}% confidence (${ms}ms)", "Planner")
        plan
    }

    /**
     * Decompose a user request into an ordered list of steps.
     * Uses the cheapest capable subsystem for each step.
     */
    private suspend fun decompose(request: String, intent: IntentResult): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        val lower = request.lowercase()
        val a11yAvailable = VoiceAccessibilityService.isRunning()
        val requiresScreen = request.contains("this") || request.contains("screen") ||
            request.contains("here") || request.contains("current")

        // ── Single-intent decomposition (simple commands) ──────────

        when (intent.intent) {
            "LAUNCH_APP" -> {
                val app = intent.entities["app"] ?: ""
                if (a11yAvailable) {
                    steps.add(PlanStep.AccessibilityAction(
                        description = "Open $app",
                        command = "open app $app"
                    ))
                } else {
                    steps.add(PlanStep.NativeAction(
                        description = "Open $app",
                        operatorId = "LAUNCH_APP",
                        params = intent.entities
                    ))
                }
            }
            "SEND_SMS" -> {
                val contact = intent.entities["contact"] ?: intent.entities["phone"] ?: ""
                val message = intent.entities["message"] ?: intent.entities["text"] ?: ""
                steps.add(PlanStep.UserConfirmation(
                    description = "Confirm send",
                    prompt = "Send message to $contact?",
                    isSensitive = true
                ))
                steps.add(PlanStep.NativeAction(
                    description = "Send SMS to $contact",
                    operatorId = "SEND_SMS",
                    params = intent.entities
                ))
            }
            "SET_TIMER", "SET_ALARM", "OPEN_SETTINGS", "ADJUST_VOLUME", "MAKE_CALL" -> {
                steps.add(PlanStep.NativeAction(
                    description = intent.intent,
                    operatorId = intent.intent,
                    params = intent.entities
                ))
            }
            "SCROLL" -> {
                val dir = intent.entities["direction"] ?: "down"
                steps.add(PlanStep.AccessibilityAction(
                    description = "Scroll $dir",
                    command = "scroll $dir"
                ))
            }
            "READ_SCREEN" -> {
                if (a11yAvailable) {
                    // Decompose: first describe via A11y, then escalate if needed
                    steps.add(PlanStep.AccessibilityAction(
                        description = "Get screen content via accessibility",
                        command = "describe_screen"
                    ))
                    steps.add(PlanStep.OcrAction(
                        description = "Read text on screen"
                    ))
                    steps.add(PlanStep.VisionAction(
                        description = "Describe screen visually",
                        question = "Describe what you see on this screen."
                    ))
                } else {
                    steps.add(PlanStep.VisionAction(
                        description = "Describe screen",
                        question = "Describe what you see on this screen."
                    ))
                }
            }
            "QUERY" -> {
                val query = intent.entities["query"] ?: request
                // Check memory first (cheapest informational lookup)
                steps.add(PlanStep.MemoryRetrieval(
                    description = "Search memory for: $query",
                    query = query
                ))
                // Then LLM for reasoning
                steps.add(PlanStep.LmReasoning(
                    description = "Reason about: $query",
                    prompt = query
                ))
            }
            "HELP" -> {
                steps.add(PlanStep.NativeAction(
                    description = "Show help",
                    operatorId = "HELP"
                ))
            }
            "UNKNOWN" -> {
                // Try LLM reasoning as fallback
                steps.add(PlanStep.LmReasoning(
                    description = "Understand request",
                    prompt = request
                ))
            }
            else -> {
                // Generic decomposition
                steps.addAll(decomposeGeneric(request, intent))
            }
        }

        return steps
    }

    /**
     * Decompose complex multi-intent requests.
     * e.g., "Reply to my latest message and then open Spotify"
     */
    private suspend fun decomposeGeneric(request: String, intent: IntentResult): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        val lower = request.lowercase()

        // Check for compound requests (contains "and then", "and", "then")
        val connectors = listOf(" and then ", " and ", " then ", ", then ", ". then ")
        var hasCompound = false

        for (connector in connectors) {
            if (lower.contains(connector)) {
                val parts = request.split(Regex(connector, RegexOption.IGNORE_CASE))
                if (parts.size >= 2) {
                    hasCompound = true
                    for (part in parts) {
                        val subIntent = intentClassifier.classify(part.trim())
                        steps.addAll(decompose(part.trim(), subIntent))
                    }
                    break
                }
            }
        }

        // Handle "this" references that need current screen context
        if (!hasCompound && requiresScreenContext(lower)) {
            steps.add(PlanStep.AccessibilityAction(
                description = "Get current screen context",
                command = "describe_screen"
            ))
            steps.add(PlanStep.LmReasoning(
                description = "Process screen context for request",
                prompt = "Given the current screen context, $request"
            ))
        }

        // Fallback: single LLM reasoning
        if (!hasCompound && steps.isEmpty()) {
            steps.add(PlanStep.LmReasoning(
                description = "Process request: $request",
                prompt = request
            ))
        }

        return steps
    }

    private fun requiresScreenContext(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("this") || lower.contains("that") ||
            lower.contains("here") || lower.contains("screen") ||
            lower.contains("current") || lower.contains("it") ||
            lower.contains("there")
    }

    /**
     * Estimate confidence that a plan will succeed before executing it.
     */
    fun estimateConfidence(plan: ExecutionPlan): Float {
        if (plan.steps.isEmpty()) return 0f

        var weightedConfidence = 0f
        for (step in plan.steps) {
            val stepConfidence = when (step) {
                is PlanStep.NativeAction -> 0.95f
                is PlanStep.AccessibilityAction -> if (VoiceAccessibilityService.isRunning()) 0.85f else 0.2f
                is PlanStep.MemoryRetrieval -> 0.7f
                is PlanStep.OcrAction -> 0.8f
                is PlanStep.LmReasoning -> 0.6f
                is PlanStep.VisionAction -> 0.7f
                is PlanStep.UserConfirmation -> 0.99f
                is PlanStep.PluginAction -> 0.5f
                is PlanStep.Conditional -> 0.8f
                is PlanStep.Loop -> 0.6f
                is PlanStep.Wait -> 0.99f
                is PlanStep.Retry -> 0.5f
            }
            weightedConfidence += stepConfidence * (1f - step.estimatedCost)
        }
        return (weightedConfidence / plan.steps.size).coerceIn(0f, 1f)
    }
}
