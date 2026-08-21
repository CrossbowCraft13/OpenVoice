package io.github.crossbowcraft13.openvoice.planner

import io.github.crossbowcraft13.openvoice.accessibility.VoiceAccessibilityService
import io.github.crossbowcraft13.openvoice.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DynamicReplanner — Handles step failures and generates recovery plans.
 *
 * When a step fails, the replanner:
 * 1. Determines WHY it failed (element not found? permission denied? timeout?)
 * 2. Attempts an alternate capability (escalates up the cost hierarchy)
 * 3. Updates the remaining plan steps
 * 4. Continues execution without restarting whenever possible
 *
 * Escalation chain:
 *   Native → Accessibility → OCR → Vision → Ask user
 *   Memory → LLM → Vision → Ask user
 *   Accessibility → OCR → Vision → LLM → Ask user
 */
@Singleton
class DynamicReplanner @Inject constructor(
    private val costModel: CostModel
) {

    /**
     * Generate a recovery plan for a failed step.
     * Returns a list of replacement steps (may be empty if recovery is impossible).
     */
    fun recover(failedStep: PlanStep, failureReason: String, retryCount: Int): RecoveryPlan {
        Logger.w("Replanning: ${failedStep.description} failed: $failureReason", "Planner")

        val lowerReason = failureReason.lowercase()

        // ── Analyze failure type ──────────────────────────────────
        return when {
            // Element not found → try a more capable detection method
            lowerReason.contains("not found") || lowerReason.contains("unable to find") -> {
                handleElementNotFound(failedStep)
            }
            // Permission denied → ask user
            lowerReason.contains("permission") -> {
                RecoveryPlan(
                    replacementSteps = listOf(
                        PlanStep.UserConfirmation(
                            description = "Grant permission",
                            prompt = "I need a permission to do this. Can you grant it?",
                            isSensitive = false
                        )
                    ),
                    strategy = RecoveryStrategy.ASK_USER
                )
            }
            // Timeout → retry with longer wait
            lowerReason.contains("timeout") || lowerReason.contains("timed out") -> {
                RecoveryPlan(
                    replacementSteps = listOf(
                        PlanStep.Wait(
                            description = "Wait before retrying",
                            durationMs = 2000
                        ),
                        failedStep
                    ),
                    strategy = RecoveryStrategy.RETRY_SAME
                )
            }
            // LLM returned empty → try vision for context
            lowerReason.contains("empty") || lowerReason.contains("no response") -> {
                val alternate = costModel.suggestAlternate(failedStep)
                RecoveryPlan(
                    replacementSteps = alternate?.let { listOf(it) } ?: emptyList(),
                    strategy = if (alternate != null) RecoveryStrategy.ESCALATE else RecoveryStrategy.ABORT
                )
            }
            // Maximum retries exceeded → escalate or abort
            lowerReason.contains("retries") || retryCount >= 3 -> {
                handleRetryExhausted(failedStep)
            }
            // Generic failure
            else -> {
                handleGenericFailure(failedStep, retryCount)
            }
        }
    }

    private fun handleElementNotFound(step: PlanStep): RecoveryPlan {
        return when (step) {
            is PlanStep.AccessibilityAction -> {
                // A11y failed → try OCR
                RecoveryPlan(
                    replacementSteps = listOf(
                        PlanStep.OcrAction("Find element via OCR: ${step.description}"),
                        step.copy(description = "Retry A11y: ${step.description}",
                            estimatedCost = costModel.costForAccessibility() * 1.5f)
                    ),
                    strategy = RecoveryStrategy.ESCALATE
                )
            }
            is PlanStep.OcrAction -> {
                // OCR failed → try vision
                RecoveryPlan(
                    replacementSteps = listOf(
                        PlanStep.VisionAction("Find element via vision: ${step.description}")
                    ),
                    strategy = RecoveryStrategy.ESCALATE
                )
            }
            is PlanStep.VisionAction -> {
                // Vision failed → ask user
                RecoveryPlan(
                    replacementSteps = listOf(
                        PlanStep.UserConfirmation(
                            description = "Ask user for help",
                            prompt = "I can't find what you're looking for. Can you help me locate it?",
                            isSensitive = false
                        )
                    ),
                    strategy = RecoveryStrategy.ASK_USER
                )
            }
            is PlanStep.NativeAction -> {
                // Native failed → try A11y if available
                if (VoiceAccessibilityService.isRunning()) {
                    RecoveryPlan(
                        replacementSteps = listOf(
                            PlanStep.AccessibilityAction(
                                description = "A11y fallback: ${step.description}",
                                command = step.description
                            )
                        ),
                        strategy = RecoveryStrategy.ESCALATE
                    )
                } else {
                    RecoveryPlan(
                        replacementSteps = emptyList(),
                        strategy = RecoveryStrategy.ABORT
                    )
                }
            }
            is PlanStep.MemoryRetrieval -> {
                RecoveryPlan(
                    replacementSteps = listOf(
                        PlanStep.LmReasoning(
                            description = "LLM fallback for: ${step.query}",
                            prompt = "Try to answer based on general knowledge: ${step.query}"
                        )
                    ),
                    strategy = RecoveryStrategy.ESCALATE
                )
            }
            else -> RecoveryPlan(emptyList(), RecoveryStrategy.ABORT)
        }
    }

    private fun handleRetryExhausted(step: PlanStep): RecoveryPlan {
        val alternate = costModel.suggestAlternate(step)
        return if (alternate != null) {
            RecoveryPlan(
                replacementSteps = listOf(alternate),
                strategy = RecoveryStrategy.ESCALATE
            )
        } else {
            RecoveryPlan(
                replacementSteps = listOf(
                    PlanStep.UserConfirmation(
                        description = "Ask user for guidance",
                        prompt = "I've tried several approaches but can't complete: ${step.description}. What should I do?",
                        isSensitive = false
                    )
                ),
                strategy = RecoveryStrategy.ASK_USER
            )
        }
    }

    private fun handleGenericFailure(step: PlanStep, retryCount: Int): RecoveryPlan {
        return when {
            retryCount < step.maxRetries -> {
                // Try again with backoff
                RecoveryPlan(
                    replacementSteps = listOf(
                        PlanStep.Wait("Backoff before retry",
                            durationMs = (1000L * (retryCount + 1))),
                        step
                    ),
                    strategy = RecoveryStrategy.RETRY_SAME
                )
            }
            else -> {
                handleRetryExhausted(step)
            }
        }
    }

    data class RecoveryPlan(
        val replacementSteps: List<PlanStep>,
        val strategy: RecoveryStrategy,
        val reason: String = ""
    )
}
