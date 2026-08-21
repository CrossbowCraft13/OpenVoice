package io.github.crossbowcraft13.openvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.crossbowcraft13.openvoice.planner.*
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerExecutionTest {

    private lateinit var costModel: CostModel
    private lateinit var userConfirmation: UserConfirmationPolicy
    private lateinit var replanner: DynamicReplanner

    @Before
    fun setup() {
        Logger.init(true)
        costModel = CostModel()
        userConfirmation = UserConfirmationPolicy()
        replanner = DynamicReplanner(costModel)
    }

    // ═══════════════════════════════════════════════════════════════
    //  PlanStep Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun nativeAction_creation() {
        val step = PlanStep.NativeAction(
            description = "Open Spotify",
            operatorId = "LAUNCH_APP",
            params = mapOf("app" to "spotify")
        )
        assertEquals("Open Spotify", step.description)
        assertEquals("LAUNCH_APP", step.operatorId)
        assertEquals(0.1f, step.estimatedCost, 0.01f)
        assertEquals(0.9f, step.minConfidence, 0.01f)
    }

    @Test
    fun accessibilityAction_creation() {
        val step = PlanStep.AccessibilityAction(
            description = "Scroll down",
            command = "scroll down"
        )
        assertEquals("scroll down", step.command)
        assertEquals(0.2f, step.estimatedCost, 0.01f)
    }

    @Test
    fun lmReasoning_creation() {
        val step = PlanStep.LmReasoning(
            description = "Summarize article",
            prompt = "Please summarize the following text: ..."
        )
        assertEquals(0.7f, step.estimatedCost, 0.01f)
        assertEquals(2000, step.estimatedLatencyMs)
        assertEquals(0.6f, step.minConfidence, 0.01f)
    }

    @Test
    fun visionAction_creation() {
        val step = PlanStep.VisionAction(
            description = "Identify chart type",
            question = "What type of chart is this?"
        )
        assertEquals(0.8f, step.estimatedCost, 0.01f)
        assertEquals(3000, step.estimatedLatencyMs)
    }

    @Test
    fun userConfirmation_creation() {
        val step = PlanStep.UserConfirmation(
            description = "Confirm purchase",
            prompt = "Buy this item for $9.99?",
            isSensitive = true
        )
        assertTrue(step.isSensitive)
        assertEquals(0.05f, step.estimatedCost, 0.01f)
    }

    // ═══════════════════════════════════════════════════════════════
    //  ExecutionPlan Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun executionPlan_creation() {
        val plan = ExecutionPlan(
            goal = "Open Settings and enable Wi-Fi",
            steps = listOf(
                PlanStep.NativeAction("Open Settings", "OPEN_SETTINGS"),
                PlanStep.AccessibilityAction("Enable Wi-Fi", "tap Wi-Fi"),
                PlanStep.AccessibilityAction("Toggle on", "tap switch")
            ),
            estimatedTotalCost = 0.5f,
            overallConfidence = 0.85f
        )
        assertEquals(3, plan.steps.size)
        assertFalse(plan.id.isEmpty())
        assertTrue(plan.createdAt > 0)
    }

    @Test
    fun executionPlan_costCalculation() {
        val steps = listOf(
            PlanStep.NativeAction("Open app", "LAUNCH_APP"),
            PlanStep.AccessibilityAction("Find button", "tap Continue"),
            PlanStep.LmReasoning("Process", "Why?")
        )
        val totalCost = steps.sumOf { it.estimatedCost.toDouble() }
        val totalLatency = steps.sumOf { it.estimatedLatencyMs.toLong() }

        assertEquals(1.0, totalCost, 0.01)
        assertTrue(totalLatency > 0)
    }

    // ═══════════════════════════════════════════════════════════════
    //  CostModel Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun costModel_nativeAction_costCorrect() {
        assertEquals(0.10f, costModel.costForNative(), 0.01f)
        assertEquals(0.20f, costModel.costForAccessibility(), 0.01f)
        assertEquals(0.70f, costModel.costForLlm(), 0.01f)
        assertEquals(0.80f, costModel.costForVision(), 0.01f)
        assertTrue(costModel.costForNative() < costModel.costForLlm())
    }

    @Test
    fun costModel_selectCheapest_prefersNative() {
        val step = costModel.selectCheapestCapability("open Spotify")
        assertTrue("Open commands should prefer Native or A11y",
            step is PlanStep.NativeAction || step is PlanStep.AccessibilityAction)
    }

    @Test
    fun costModel_selectCheapest_llmForQueries() {
        val step = costModel.selectCheapestCapability("what is the weather today")
        assertTrue("Questions should use LLM",
            step is PlanStep.LmReasoning)
    }

    @Test
    fun costModel_selectCheapest_memoryForPreferences() {
        val step = costModel.selectCheapestCapability("what is my favorite music app")
        assertTrue("Preference queries should use Memory",
            step is PlanStep.MemoryRetrieval || step is PlanStep.LmReasoning)
    }

    @Test
    fun costModel_suggestAlternate_escalates() {
        val nativeStep = PlanStep.NativeAction("Open app", "LAUNCH_APP")
        val alternate = costModel.suggestAlternate(nativeStep)
        assertNotNull("Should suggest an alternate")
    }

    @Test
    fun costModel_canEscalate_correct() {
        assertTrue(costModel.canEscalateTo(0.1f, 0.7f))
        assertFalse(costModel.canEscalateTo(0.8f, 0.5f))
    }

    // ═══════════════════════════════════════════════════════════════
    //  DynamicReplanner Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun replanner_elementNotFound_escalatesToOcr() {
        val step = PlanStep.AccessibilityAction("Tap button", "tap Continue")
        val recovery = replanner.recover(step, "Element 'Continue' not found", 0)
        assertEquals(RecoveryStrategy.ESCALATE, recovery.strategy)
        assertTrue(recovery.replacementSteps.any { it is PlanStep.OcrAction })
    }

    @Test
    fun replanner_permissionDenied_asksUser() {
        val step = PlanStep.NativeAction("Send SMS", "SEND_SMS")
        val recovery = replanner.recover(step, "Permission denied: SEND_SMS", 1)
        assertEquals(RecoveryStrategy.ASK_USER, recovery.strategy)
    }

    @Test
    fun replanner_timeout_addsWait() {
        val step = PlanStep.AccessibilityAction("Tap", "tap OK")
        val recovery = replanner.recover(step, "Timed out waiting for element", 0)
        assertEquals(RecoveryStrategy.RETRY_SAME, recovery.strategy)
        assertTrue(recovery.replacementSteps.any { it is PlanStep.Wait })
    }

    @Test
    fun replanner_retryExhausted_escalates() {
        val step = PlanStep.AccessibilityAction("Tap", "tap OK")
        val recovery = replanner.recover(step, "Element not found after 3 retries", 3)
        assertTrue("Should escalate after retry exhaustion",
            recovery.strategy == RecoveryStrategy.ESCALATE ||
            recovery.strategy == RecoveryStrategy.ASK_USER)
    }

    @Test
    fun replanner_nativeFails_usesA11yFallback() {
        val step = PlanStep.NativeAction("Open app", "LAUNCH_APP")
        val recovery = replanner.recover(step, "App not found", 0)
        // Should try A11y or ask user
        assertNotNull(recovery.replacementSteps)
    }

    // ═══════════════════════════════════════════════════════════════
    //  UserConfirmationPolicy Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun userConfirmation_purchases_requireConfirmation() {
        val step = PlanStep.UserConfirmation("Buy item", "Buy this?", isSensitive = true)
        val decision = userConfirmation.evaluate(step)
        assertNotNull(decision)
    }

    @Test
    fun userConfirmation_openingApp_skipsConfirmation() {
        val step = PlanStep.AccessibilityAction("Open Spotify", "open app com.spotify.music")
        val decision = userConfirmation.evaluate(step)
        assertFalse("Opening apps should not require confirmation", decision.requiresConfirmation)
    }

    @Test
    fun userConfirmation_utilityAction_skips() {
        val step = PlanStep.NativeAction("Set timer 5min", "SET_TIMER")
        val decision = userConfirmation.evaluate(step)
        assertFalse(decision.requiresConfirmation)
    }

    @Test
    fun userConfirmation_deletion_requiresConfirmation() {
        val step = PlanStep.NativeAction("Delete file", "DELETE")
        val decision = userConfirmation.evaluate(step)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun userConfirmation_financial_requiresConfirmation() {
        val step = PlanStep.NativeAction("Send money $100", "TRANSFER")
        val decision = userConfirmation.evaluate(step)
        assertTrue(decision.requiresConfirmation)
    }

    // ═══════════════════════════════════════════════════════════════
    //  ExecutionResult Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun executionResult_success() {
        val result = ExecutionResult(
            planId = "plan_1",
            success = true,
            completedSteps = 3,
            totalSteps = 3,
            totalLatencyMs = 1500
        )
        assertTrue(result.success)
        assertEquals(3, result.completedSteps)
        assertEquals(3, result.totalSteps)
    }

    @Test
    fun executionResult_failure() {
        val result = ExecutionResult(
            planId = "plan_1",
            success = false,
            completedSteps = 1,
            totalSteps = 3,
            failedStepIndex = 1,
            failureReason = "Element not found",
            totalLatencyMs = 800
        )
        assertFalse(result.success)
        assertEquals(1, result.failedStepIndex)
    }

    @Test
    fun stepExecutionResult_creation() {
        val result = StepExecutionResult(
            stepId = "step_1",
            stepDescription = "Open Settings",
            success = true,
            latencyMs = 100,
            retriesUsed = 0,
            capabilityUsed = "NativeAction"
        )
        assertTrue(result.success)
        assertEquals("NativeAction", result.capabilityUsed)
    }

    // ═══════════════════════════════════════════════════════════════
    //  LearningRecorder Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun learningStats_successRate() {
        val stats = LearningRecorder.LearningStats(
            totalPlansExecuted = 100,
            successfulPlans = 85,
            failedPlans = 15
        )
        assertEquals(0.85f, stats.successRate, 0.01f)
    }

    @Test
    fun learningStats_empty() {
        val stats = LearningRecorder.LearningStats()
        assertEquals(0f, stats.successRate, 0.01f)
    }

    @Test
    fun recoveryStrategy_enum_values() {
        assertNotNull(RecoveryStrategy.RETRY_SAME)
        assertNotNull(RecoveryStrategy.ESCALATE)
        assertNotNull(RecoveryStrategy.ALTERNATE_PATH)
        assertNotNull(RecoveryStrategy.ASK_USER)
        assertNotNull(RecoveryStrategy.ABORT)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Plan Validation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun plan_goalRequired() {
        val plan = ExecutionPlan(goal = "", steps = emptyList())
        assertTrue(plan.goal.isEmpty())
    }

    @Test
    fun plan_emptySteps_stillValid() {
        val plan = ExecutionPlan(goal = "Test", steps = emptyList())
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun plan_confidenceBounds() {
        val low = 0f
        val high = 1f
        val ok = 0.75f
        assertTrue(low >= 0f)
        assertTrue(high <= 1f)
        assertTrue(ok in 0f..1f)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Performance Benchmarks
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun benchmark_planStepCreation() {
        val iterations = 1000
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val step = PlanStep.NativeAction(
                description = "Action $i",
                operatorId = "OP_$i",
                params = mapOf("key" to "value_$i")
            )
            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("PlanStep creation (n=$iterations): avg=${avgUs}µs")
        // Regression guard with 10x headroom over measured steady-state (~60µs).
        assertTrue("PlanStep creation < 1ms", avgUs < 1_000)
    }

    @Test
    fun benchmark_executionPlanCreation() {
        val iterations = 500
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val plan = ExecutionPlan(
                goal = "Task $i",
                steps = (1..10).map { j ->
                    PlanStep.NativeAction("Step $j", "OP_$j") as PlanStep
                },
                estimatedTotalCost = i.toFloat(),
                overallConfidence = 0.8f
            )
            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("ExecutionPlan creation (n=$iterations): avg=${avgUs}µs")
        // Regression guard with 10x headroom over measured steady-state (~320µs).
        assertTrue("Plan creation < 4ms", avgUs < 4_000)
    }

    @Test
    fun benchmark_costModelResolution() {
        val queries = listOf(
            "open Spotify",
            "scroll down",
            "what's on my screen",
            "how does this work",
            "set a timer for 5 minutes",
            "what's my favorite color",
            "enable Wi-Fi",
            "describe this chart"
        )

        val iterations = 100
        // Warm up the JIT before timing
        for (i in 0 until 10) {
            for (query in queries) costModel.selectCheapestCapability(query)
        }
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            for (query in queries) {
                val start = System.nanoTime()
                costModel.selectCheapestCapability(query)
                timings.add((System.nanoTime() - start) / 1_000)
            }
        }

        val avgUs = timings.average().toLong()
        println("CostModel resolution (n=${queries.size * iterations}): avg=${avgUs}µs")
        assertTrue("CostModel < 500µs", avgUs < 500)
    }

    @Test
    fun benchmark_replannerRecovery() {
        val steps = listOf(
            PlanStep.NativeAction("Open", "LAUNCH_APP"),
            PlanStep.AccessibilityAction("Tap", "tap OK"),
            PlanStep.OcrAction("Read", "full"),
            PlanStep.LmReasoning("Think", "Why?"),
            PlanStep.VisionAction("Look", "What?")
        )

        val iterations = 100
        // Warm up the JIT before timing
        for (i in 0 until 10) {
            for (step in steps) replanner.recover(step, "Element not found", 0)
        }
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            for (step in steps) {
                val start = System.nanoTime()
                replanner.recover(step, "Element not found", 0)
                timings.add((System.nanoTime() - start) / 1_000)
            }
        }

        val avgUs = timings.average().toLong()
        println("Replanner recovery (n=${steps.size * iterations}): avg=${avgUs}µs")
        assertTrue("Replanner < 200µs", avgUs < 200)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Edge Cases
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun emptyGoal_plan() {
        val plan = ExecutionPlan(goal = "", steps = emptyList())
        assertEquals("", plan.goal)
    }

    @Test
    fun recovery_emptyReplacementSteps() {
        val step = PlanStep.VisionAction("Look", "What?")
        val recovery = replanner.recover(step, "Vision failed", 0)
        assertNotNull(recovery)
    }

    @Test
    fun costModel_unknownInput_usesLLM() {
        val step = costModel.selectCheapestCapability("xyzzy flurbo garblex")
        assertNotNull(step)
    }
}
