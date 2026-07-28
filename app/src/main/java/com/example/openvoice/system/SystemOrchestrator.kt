package com.example.openvoice.system

import android.content.Context
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.memory.MemoryEngine
import com.example.openvoice.perception.PerceptionEngine
import com.example.openvoice.planner.Planner
import com.example.openvoice.planner.ExecutionEngine
import com.example.openvoice.planner.LearningRecorder
import com.example.openvoice.system.explain.ExplainMode
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SystemOrchestrator — Unifies all subsystems into one coherent assistant.
 *
 * This is the assistant's main entry point. It wires together:
 * - Voice pipeline (VAD → wake word → STT)
 * - Intent classification
 * - Planning (Planner + ExecutionEngine)
 * - Memory
 * - Perception
 * - ExplainMode
 *
 * A single call to processUserInput() runs the entire stack.
 */
@Singleton
class SystemOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val planner: Planner,
    private val executionEngine: ExecutionEngine,
    private val memoryEngine: MemoryEngine,
    private val perceptionEngine: PerceptionEngine,
    private val inferenceEngine: InferenceEngine,
    private val blackboard: TaskBlackboard,
    private val explainMode: ExplainMode,
    private val learningRecorder: LearningRecorder
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var initialized = false

    data class AssistantResponse(
        val text: String = "",
        val planSteps: Int = 0,
        val executionTimeMs: Long = 0,
        val success: Boolean = true,
        val explanation: String = ""
    )

    /**
     * Initialize all subsystems.
     * Returns true if initialization succeeded.
     */
    suspend fun initialize(): Boolean {
        Logger.i("SystemOrchestrator: initializing...", "System")
        val start = System.currentTimeMillis()

        // Initialize in dependency order
        val memoryOk = memoryEngine.initialize()
        val perceptionOk = true // perception initialized lazily

        initialized = memoryOk
        Logger.i("SystemOrchestrator: initialized in ${System.currentTimeMillis() - start}ms", "System")
        return initialized
    }

    /**
     * Process a user request end-to-end.
     *
     * 1. Record the request
     * 2. Plan the execution
     * 3. Execute the plan
     * 4. Record the result
     * 5. Generate explanation
     * 6. Return response
     */
    suspend fun processUserInput(userRequest: String): AssistantResponse {
        if (!initialized) {
            Logger.w("System not initialized", "System")
            return AssistantResponse("System is initializing...", 0, 0, false)
        }

        val start = System.currentTimeMillis()
        Logger.i("Processing: $userRequest", "System")

        // 1. Create plan
        val plan = planner.createPlan(userRequest)
        explainMode.recordDecision(plan.steps.firstOrNull() ?: return AssistantResponse(
            "I couldn't figure out how to do that.", 0, 0, false,
            "Planner returned no steps for: $userRequest"), "Cheapest capable subsystem")

        // 2. Execute plan
        val result = executionEngine.execute(plan)
        explainMode.recordExecution(plan, result)

        // 3. Record learning
        learningRecorder.recordExecution(result, plan)

        // 4. Generate explanation
        val explanation = explainMode.explainDecision("what happened")

        // 5. Build response
        val responseText = if (result.success) {
            val lastStep = result.stepResults.lastOrNull()
            if (lastStep?.success == true) "Done."
            else "I've completed the task."
        } else {
            "I wasn't able to complete that. ${result.failureReason}"
        }

        val elapsed = System.currentTimeMillis() - start
        Logger.i("Response: $responseText ($elapsed ms)", "System")

        return AssistantResponse(
            text = responseText,
            planSteps = plan.steps.size,
            executionTimeMs = elapsed,
            success = result.success,
            explanation = explanation
        )
    }

    /**
     * Explain the last decision.
     */
    fun explainLastAction(): String {
        val explanation = explainMode.explainLast()
        return buildString {
            appendLine("🎯 Goal: ${explanation.goal}")
            appendLine()
            for ((i, step) in explanation.steps.withIndex()) {
                appendLine("${i + 1}. ${step.description}")
                appendLine("   → ${step.capability}")
                appendLine("   ${step.why}")
            }
            appendLine()
            appendLine("⏱ ${explanation.totalTimeMs}ms | " +
                "🎯 ${"%.0f".format(explanation.overallConfidence * 100)}% confidence | " +
                "🔋 ${explanation.batteryImpact} impact")
        }
    }

    fun isInitialized() = initialized

    /**
     * Get a system status summary.
     */
    fun getStatusSummary(): String {
        val state = blackboard.state.value
        return buildString {
            appendLine("Status: ${if (initialized) "Running" else "Starting"}")
            appendLine("Engine: ${if (inferenceEngine.isReady) "AI Ready" else "No Model"}")
            appendLine("Task: ${state.completionStatus}")
            if (state.userGoal.isNotEmpty()) {
                appendLine("Current: ${state.userGoal.take(60)}")
                appendLine("Progress: ${state.currentStepIndex}/${state.plan.size}")
            }
        }.trimEnd()
    }
}
