package com.example.openvoice.planner

import com.example.openvoice.memory.MemoryCategory
import com.example.openvoice.memory.MemoryEngine
import com.example.openvoice.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LearningRecorder — Records plan execution history into MemoryEngine.
 *
 * Tracks:
 * - Successful plans (for reuse)
 * - Failed plans (to avoid repeating mistakes)
 * - Retry counts per capability type
 * - Average execution time per plan type
 * - User corrections
 *
 * The assistant gradually improves over time by retrieving
 * past experiences before creating new plans.
 */
@Singleton
class LearningRecorder @Inject constructor(
    private val memoryEngine: MemoryEngine
) {

    /**
     * Record a completed plan execution.
     */
    suspend fun recordExecution(result: ExecutionResult, plan: ExecutionPlan) {
        if (!result.success) {
            // Record the failure
            memoryEngine.remember(
                key = "plan_failure_${plan.id.take(8)}",
                value = "Plan '${plan.goalSummary}' failed at step ${result.failedStepIndex}: ${result.failureReason}",
                category = MemoryCategory.LEARNED_FACT,
                confidence = 1.0f,
                source = "planner"
            )
        }

        // Record execution statistics
        memoryEngine.remember(
            key = "plan_exec_${plan.id.take(8)}",
            value = "Goal: '${plan.goal.take(100)}' | " +
                "Steps: ${result.completedSteps}/${result.totalSteps} | " +
                "Time: ${result.totalLatencyMs}ms | " +
                "Retries: ${result.retriesUsed} | " +
                "Success: ${result.success}",
            category = MemoryCategory.LEARNED_FACT,
            confidence = if (result.success) 0.9f else 0.5f,
            source = "planner"
        )
    }

    /**
     * Record a capability-specific failure.
     * This helps the planner avoid using capabilities that
     * have a history of failure in similar contexts.
     */
    suspend fun recordCapabilityFailure(capabilityType: String, context: String, error: String) {
        val key = "cap_fail_${capabilityType.lowercase().replace(" ", "_")}"
        val existing = memoryEngine.retrieve(key)
        val newCount = (existing?.accessCount ?: 0) + 1

        memoryEngine.remember(
            key = key,
            value = "Capability '$capabilityType' failed $newCount times. " +
                "Last context: $context. Error: $error",
            category = MemoryCategory.LEARNED_FACT,
            confidence = 0.8f,
            source = "planner"
        )
    }

    /**
     * Record a successful capability use.
     * Increases the planner's confidence in using this capability
     * for similar tasks.
     */
    suspend fun recordCapabilitySuccess(capabilityType: String, context: String) {
        val key = "cap_success_${capabilityType.lowercase().replace(" ", "_")}"
        val existing = memoryEngine.retrieve(key)
        val newCount = (existing?.accessCount ?: 0) + 1

        memoryEngine.remember(
            key = key,
            value = "Capability '$capabilityType' succeeded $newCount times. " +
                "Last context: $context",
            category = MemoryCategory.LEARNED_FACT,
            confidence = 0.9f,
            source = "planner"
        )
    }

    /**
     * Retrieve the success rate for a given capability type.
     */
    suspend fun getCapabilitySuccessRate(capabilityType: String): Float {
        val successKey = "cap_success_${capabilityType.lowercase().replace(" ", "_")}"
        val failKey = "cap_fail_${capabilityType.lowercase().replace(" ", "_")}"

        val successes = memoryEngine.retrieve(successKey)
        val failures = memoryEngine.retrieve(failKey)

        val successCount = successes?.accessCount ?: 0
        val failureCount = failures?.accessCount ?: 0
        val total = successCount + failureCount

        return if (total > 0) successCount.toFloat() / total else 0.5f
    }

    /**
     * Record a user correction.
     */
    suspend fun recordUserCorrection(
        originalRequest: String,
        userFeedback: String
    ) {
        memoryEngine.remember(
            key = "user_correction_${System.currentTimeMillis().toString().takeLast(6)}",
            value = "User corrected: '$originalRequest' → '$userFeedback'",
            category = MemoryCategory.LEARNED_FACT,
            confidence = 1.0f,
            source = "user"
        )
        Logger.i("User correction recorded: $userFeedback", "Learning")
    }

    /**
     * Get learning statistics.
     */
    suspend fun getStats(): LearningStats {
        val planExecs = memoryEngine.searchByKeyword("plan_exec_")
        val capFailures = memoryEngine.searchByKeyword("cap_fail_")
        val capSuccesses = memoryEngine.searchByKeyword("cap_success_")

        return LearningStats(
            totalPlansExecuted = planExecs.size,
            successfulPlans = planExecs.count { it.value.contains("Success: true") },
            failedPlans = planExecs.count { it.value.contains("Success: false") },
            capabilitiesWithFailures = capFailures.size,
            capabilitiesWithSuccesses = capSuccesses.size
        )
    }

    data class LearningStats(
        val totalPlansExecuted: Int = 0,
        val successfulPlans: Int = 0,
        val failedPlans: Int = 0,
        val capabilitiesWithFailures: Int = 0,
        val capabilitiesWithSuccesses: Int = 0
    ) {
        val successRate: Float
            get() = if (totalPlansExecuted > 0)
                successfulPlans.toFloat() / totalPlansExecuted else 0f
    }
}
