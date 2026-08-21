package io.github.crossbowcraft13.openvoice.planner

/**
 * PlanStep — A single executable step in a plan.
 *
 * Each step declares:
 * - What capability it needs (step type)
 * - What to execute (command description)
 * - Estimated cost and latency
 * - Confidence threshold
 * - Success criteria
 * - Preconditions
 * - Recovery strategy
 */
sealed class PlanStep(
    open val id: String = java.util.UUID.randomUUID().toString(),
    open val description: String,
    open val estimatedCost: Float = 0.5f,  // 0.0–1.0, lower = cheaper
    open val estimatedLatencyMs: Long = 100,
    open val minConfidence: Float = 0.5f,
    open val successCriteria: String = "",
    open val preconditions: List<String> = emptyList(),
    open val rollbackAction: String = "",
    open val maxRetries: Int = 2
) {
    /** Execute this step via a Native Android API. */
    data class NativeAction(
        override val description: String,
        val operatorId: String,
        val params: Map<String, String> = emptyMap(),
        override val estimatedCost: Float = 0.1f,
        override val estimatedLatencyMs: Long = 50,
        override val minConfidence: Float = 0.9f
    ) : PlanStep(description = description, estimatedCost = estimatedCost,
        estimatedLatencyMs = estimatedLatencyMs, minConfidence = minConfidence)

    /** Execute this step via the Accessibility Service. */
    data class AccessibilityAction(
        override val description: String,
        val command: String,
        override val estimatedCost: Float = 0.2f,
        override val estimatedLatencyMs: Long = 500,
        override val minConfidence: Float = 0.8f
    ) : PlanStep(description = description, estimatedCost = estimatedCost,
        estimatedLatencyMs = estimatedLatencyMs, minConfidence = minConfidence)

    /** Retrieve information from memory. */
    data class MemoryRetrieval(
        override val description: String,
        val query: String,
        override val estimatedCost: Float = 0.15f,
        override val estimatedLatencyMs: Long = 100
    ) : PlanStep(description = description, estimatedCost = estimatedCost,
        estimatedLatencyMs = estimatedLatencyMs)

    /** Analyze screen with OCR. */
    data class OcrAction(
        override val description: String,
        val region: String = "full",
        override val estimatedCost: Float = 0.3f,
        override val estimatedLatencyMs: Long = 200
    ) : PlanStep(description = description, estimatedCost = estimatedCost,
        estimatedLatencyMs = estimatedLatencyMs)

    /** Invoke the local LLM for reasoning. */
    data class LmReasoning(
        override val description: String,
        val prompt: String,
        override val estimatedCost: Float = 0.7f,
        override val estimatedLatencyMs: Long = 2000,
        override val minConfidence: Float = 0.6f
    ) : PlanStep(description = description, estimatedCost = estimatedCost,
        estimatedLatencyMs = estimatedLatencyMs, minConfidence = minConfidence)

    /** Analyze screen with vision model. */
    data class VisionAction(
        override val description: String,
        val question: String = "",
        override val estimatedCost: Float = 0.8f,
        override val estimatedLatencyMs: Long = 3000
    ) : PlanStep(description = description, estimatedCost = estimatedCost,
        estimatedLatencyMs = estimatedLatencyMs)

    /** Ask user for confirmation or clarification. */
    data class UserConfirmation(
        override val description: String,
        val prompt: String,
        val isSensitive: Boolean = false,
        override val estimatedCost: Float = 0.05f,
        override val estimatedLatencyMs: Long = 5000  // Human response time
    ) : PlanStep(description = description, estimatedCost = estimatedCost,
        estimatedLatencyMs = estimatedLatencyMs)

    /** Execute a plugin capability. */
    data class PluginAction(
        override val description: String,
        val pluginId: String,
        val action: String,
        val params: Map<String, String> = emptyMap(),
        override val estimatedCost: Float = 0.4f,
        override val estimatedLatencyMs: Long = 1000
    ) : PlanStep(description = description, estimatedCost = estimatedCost,
        estimatedLatencyMs = estimatedLatencyMs)

    /** Conditional branch: only execute if condition is met. */
    data class Conditional(
        override val description: String,
        val condition: String,
        val ifSteps: List<PlanStep>,
        val elseSteps: List<PlanStep> = emptyList(),
        override val estimatedCost: Float = 0.05f
    ) : PlanStep(description = description, estimatedCost = estimatedCost,
        estimatedLatencyMs = 0)

    /** Execute steps in a loop over a collection. */
    data class Loop(
        override val description: String,
        val over: List<String>,
        val body: List<PlanStep>,
        override val estimatedCost: Float = 0.5f
    ) : PlanStep(description = description, estimatedCost = estimatedCost)

    /** Wait for a condition or duration. */
    data class Wait(
        override val description: String,
        val durationMs: Long = 1000,
        val condition: String = "",
        override val estimatedCost: Float = 0.01f
    ) : PlanStep(description = description, estimatedCost = estimatedCost,
        estimatedLatencyMs = durationMs)

    /** Retry a previous step. */
    data class Retry(
        override val description: String,
        val stepId: String,
        val alternateCapability: String = "",
        override val estimatedCost: Float = 0.3f,
        override val estimatedLatencyMs: Long = 1000
    ) : PlanStep(description = description, estimatedCost = estimatedCost,
        estimatedLatencyMs = estimatedLatencyMs)
}

/**
 * ExecutionPlan — The complete plan produced by the Planner.
 */
data class ExecutionPlan(
    val id: String = java.util.UUID.randomUUID().toString(),
    val goal: String,                           // Original user request
    val goalSummary: String = "",                // Short description
    val steps: List<PlanStep>,
    val dependencies: Map<String, List<String>> = emptyMap(),  // stepId → [dependsOnStepIds]
    val estimatedTotalCost: Float = 0f,
    val estimatedTotalLatencyMs: Long = 0,
    val overallConfidence: Float = 0f,
    val recoveryStrategy: RecoveryStrategy = RecoveryStrategy.ESCALATE,
    val rollbackPlan: List<PlanStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "planner"
)

enum class RecoveryStrategy {
    RETRY_SAME,       // Try the same step again
    ESCALATE,         // Try a more expensive capability
    ALTERNATE_PATH,   // Try a different approach
    ASK_USER,         // Ask the user what to do
    ABORT             // Give up
}

/**
 * Result of a plan execution.
 */
data class ExecutionResult(
    val planId: String,
    val success: Boolean,
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val failedStepIndex: Int = -1,
    val failureReason: String = "",
    val totalLatencyMs: Long = 0,
    val retriesUsed: Int = 0,
    val recoveryAttempted: Boolean = false,
    val recoverySucceeded: Boolean = false,
    val stepResults: List<StepExecutionResult> = emptyList()
)

data class StepExecutionResult(
    val stepId: String,
    val stepDescription: String,
    val success: Boolean,
    val latencyMs: Long = 0,
    val error: String = "",
    val retriesUsed: Int = 0,
    val capabilityUsed: String = ""
)
