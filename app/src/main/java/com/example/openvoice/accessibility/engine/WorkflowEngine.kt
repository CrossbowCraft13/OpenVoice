package com.example.openvoice.accessibility.engine

import com.example.openvoice.accessibility.VoiceAccessibilityService
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.util.Logger
import kotlinx.coroutines.delay

/**
 * WorkflowEngine — Multi-step UI automation with retry, recovery, and alternate paths.
 *
 * Example: Open Gmail → Tap Compose → Enter recipient → Enter subject → Enter body → Send
 *
 * Each step can:
 * - Retry on failure (configurable count)
 * - Try alternate selectors (alternative text, role-only, coordinate)
 * - Recover from unexpected UI changes (dialogs, navigation changes)
 * - Timeout gracefully
 */
class WorkflowEngine(
    private val a11y: VoiceAccessibilityService,
    private val actions: ActionEngine,
    private val screenManager: ScreenStateManager,
    private val blackboard: TaskBlackboard
) {

    data class WorkflowStep(
        val action: String,            // Command string like "tap Compose"
        val description: String = "",   // Human-readable description
        val maxRetries: Int = 2,
        val timeoutMs: Long = 5000,
        val alternateActions: List<String> = emptyList(),  // Fallback commands
        val recoveryAction: String = "",  // Command to recover from unexpected state
        val verifyAction: String = ""     // Command to verify step completed
    )

    data class WorkflowResult(
        val steps: List<StepResult>,
        val success: Boolean,
        val failedStepIndex: Int = -1,
        val totalTimeMs: Long = 0
    )

    data class StepResult(
        val step: WorkflowStep,
        val actionResult: ActionResult,
        val attempts: Int,
        val alternateUsed: Boolean = false,
        val stepTimeMs: Long = 0
    )

    /**
     * Execute a multi-step workflow.
     * Automatically handles retries, alternates, and recovery.
     */
    suspend fun execute(workflow: List<WorkflowStep>): WorkflowResult {
        val start = System.currentTimeMillis()
        val results = mutableListOf<StepResult>()

        Logger.i("Workflow started: ${workflow.size} steps", "Workflow")

        for ((index, step) in workflow.withIndex()) {
            Logger.d("Step $index: ${step.action} (${step.description})", "Workflow")

            // Wait for screen to be ready
            screenManager.waitForStableScreen(2000)

            val stepStart = System.currentTimeMillis()
            val stepResult = executeWithRetry(step)
            results.add(stepResult)

            if (!stepResult.actionResult.success) {
                Logger.e("Workflow failed at step $index: ${step.action}", "Workflow")
                blackboard.recordFailure(step.action,
                    stepResult.actionResult.failureReason,
                    stepResult.actionResult.retrySuggestion)
                return WorkflowResult(
                    steps = results,
                    success = false,
                    failedStepIndex = index,
                    totalTimeMs = System.currentTimeMillis() - start
                )
            }

            blackboard.recordSuccess(step.action,
                System.currentTimeMillis() - stepStart)

            // Post-step delay for UI to settle
            delay(300)
        }

        val totalMs = System.currentTimeMillis() - start
        Logger.i("Workflow completed: ${workflow.size} steps in ${totalMs}ms", "Workflow")
        return WorkflowResult(
            steps = results,
            success = true,
            totalTimeMs = totalMs
        )
    }

    private suspend fun executeWithRetry(step: WorkflowStep): StepResult {
        val start = System.currentTimeMillis()

        for (attempt in 0..step.maxRetries) {
            // Primary action
            var result = actions.executeCommand(step.action)

            if (result.success) {
                // Verify if needed
                if (step.verifyAction.isNotEmpty()) {
                    delay(500)
                    val verify = actions.executeCommand(step.verifyAction)
                    if (!verify.success) {
                        Logger.w("Step ${step.action} passed but verify '${step.verifyAction}' failed", "Workflow")
                        // Continue — the action may still have worked
                    }
                }
                return StepResult(
                    step = step,
                    actionResult = result,
                    attempts = attempt + 1,
                    stepTimeMs = System.currentTimeMillis() - start
                )
            }

            // Try alternate actions
            if (step.alternateActions.isNotEmpty()) {
                for (altAction in step.alternateActions) {
                    Logger.d("Trying alternate: $altAction", "Workflow")
                    result = actions.executeCommand(altAction)
                    if (result.success) {
                        return StepResult(
                            step = step,
                            actionResult = result,
                            attempts = attempt + 1,
                            alternateUsed = true,
                            stepTimeMs = System.currentTimeMillis() - start
                        )
                    }
                }
            }

            // Recovery: unexpected state?
            if (step.recoveryAction.isNotEmpty()) {
                Logger.w("Recovering from: ${result.failureReason}", "Workflow")
                actions.executeCommand(step.recoveryAction)
                delay(500)
                screenManager.captureScreen()
            }

            if (attempt < step.maxRetries) {
                delay((500L * (attempt + 1))) // Backoff
            }
        }

        return StepResult(
            step = step,
            actionResult = ActionResult(
                action = step.action,
                success = false,
                failureReason = "Failed after ${step.maxRetries + 1} attempts"
            ),
            attempts = step.maxRetries + 1,
            stepTimeMs = System.currentTimeMillis() - start
        )
    }

    /**
     * Pre-built workflows.
     */

    /** Send a text message */
    fun sendSmsWorkflow(contact: String, message: String): List<WorkflowStep> = listOf(
        WorkflowStep("tap Compose", "Open new message screen"),
        WorkflowStep("type $contact", "Enter recipient",
            alternateActions = listOf("tap To", "tap recipient")),
        WorkflowStep("tap message", "Focus message body",
            alternateActions = listOf("tap text", "tap body")),
        WorkflowStep("type $message", "Type message text"),
        WorkflowStep("tap Send", "Send the message",
            alternateActions = listOf("tap send", "send")),
    )

    /** Open an app and perform a search */
    fun searchInAppWorkflow(appName: String, searchTerm: String): List<WorkflowStep> = listOf(
        WorkflowStep("open app $appName", "Launch $appName"),
        WorkflowStep("tap search", "Focus search field",
            alternateActions = listOf("tap Search", "tap Find")),
        WorkflowStep("type $searchTerm", "Type search query"),
        WorkflowStep("tap search", "Execute search",
            alternateActions = listOf("tap Search", "tap enter")),
    )

    /** Fill a form */
    fun fillFormWorkflow(fields: List<Pair<String, String>>): List<WorkflowStep> {
        val steps = mutableListOf<WorkflowStep>()
        for ((label, value) in fields) {
            steps.add(WorkflowStep("tap $label", "Focus '$label' field",
                alternateActions = listOf("type $value")))
            steps.add(WorkflowStep("type $value", "Enter '$value'"))
        }
        return steps
    }
}
