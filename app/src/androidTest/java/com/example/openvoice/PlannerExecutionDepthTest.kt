package com.example.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.ai.AiSettings
import com.example.openvoice.ai.DeviceProfiler
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.memory.KnowledgeGraph
import com.example.openvoice.memory.MemoryEngine
import com.example.openvoice.memory.MemoryLifecycle
import com.example.openvoice.memory.MemoryCategory
import com.example.openvoice.memory.VectorStore
import com.example.openvoice.memory.encryption.MemoryEncryption
import com.example.openvoice.operator.OperatorRegistry
import com.example.openvoice.perception.OcrEngine
import com.example.openvoice.perception.PerceptionEngine
import com.example.openvoice.perception.ScreenshotPipeline
import com.example.openvoice.perception.VisualMemoryCache
import com.example.openvoice.perception.vision.VisionRuntime
import com.example.openvoice.planner.ExecutionEngine
import com.example.openvoice.planner.ExecutionPlan
import com.example.openvoice.planner.ExecutionResult
import com.example.openvoice.planner.LearningRecorder
import com.example.openvoice.planner.PlanStep
import com.example.openvoice.planner.DynamicReplanner
import com.example.openvoice.planner.CostModel
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.util.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerExecutionDepthTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
        context.deleteDatabase("openvoice_memories.db")
        context.deleteDatabase("openvoice_knowledge.db")
    }

    private data class Harness(
        val engine: ExecutionEngine,
        val memory: MemoryEngine,
        val blackboard: TaskBlackboard
    )

    private fun harness(): Harness {
        val settings = AiSettings(context)
        val profiler = DeviceProfiler(context)
        val inference = InferenceEngine(context, settings, profiler)
        val blackboard = TaskBlackboard()
        val vectorStore = VectorStore(context, inference)
        val graph = KnowledgeGraph(context)
        val encryption = MemoryEncryption(context)
        val lifecycle = MemoryLifecycle(context, inference, encryption, vectorStore, graph)
        val memory = MemoryEngine(context, vectorStore, graph, encryption, lifecycle, blackboard)
        val perception = PerceptionEngine(
            ScreenshotPipeline(context),
            OcrEngine(context),
            VisionRuntime(inference),
            VisualMemoryCache(),
            blackboard
        )
        return Harness(
            ExecutionEngine(
                context = context,
                operatorRegistry = OperatorRegistry(),
                perceptionEngine = perception,
                memoryEngine = memory,
                inferenceEngine = inference,
                blackboard = blackboard,
                replanner = DynamicReplanner(CostModel())
            ),
            memory,
            blackboard
        )
    }

    @Test
    fun execute_successfulPlan_coversConfirmationConditionalLoopAndWait() = runBlocking {
        val h = harness()
        val plan = ExecutionPlan(
            goal = "safe local workflow",
            steps = listOf(
                PlanStep.UserConfirmation("Confirm", "Continue?"),
                PlanStep.Conditional("Check condition", "true", emptyList()),
                PlanStep.Loop(
                    "Process items",
                    over = listOf("one", "two"),
                    body = listOf(PlanStep.UserConfirmation("Confirm item", "Continue item?"))
                ),
                PlanStep.Wait("Finish", durationMs = 0)
            )
        )

        val result = h.engine.execute(plan)
        assertTrue(result.success)
        assertEquals(4, result.completedSteps)
        assertEquals(4, result.totalSteps)
        assertNotNull(h.engine.getCurrentPlan())
        assertEquals("COMPLETED", h.blackboard.state.value.completionStatus.name)
    }

    @Test
    fun execute_failedPlan_retriesAndUsesRecovery() = runBlocking {
        val h = harness()
        val result = h.engine.execute(
            ExecutionPlan(
                goal = "unsupported plugin",
                steps = listOf(PlanStep.PluginAction("Use missing plugin", "missing", "run"))
            )
        )

        assertFalse(result.success)
        assertEquals(1, result.totalSteps)
        assertEquals(0, result.failedStepIndex)
        assertEquals(2, result.retriesUsed)
        assertTrue(result.failureReason.isNotEmpty())
        assertEquals("FAILED", h.blackboard.state.value.completionStatus.name)
    }

    @Test
    fun execute_cancelledBetweenSteps_returnsCancelledResult() = runBlocking {
        val h = harness()
        val deferred = async {
            h.engine.execute(
                ExecutionPlan(
                    goal = "cancel me",
                    steps = listOf(
                        PlanStep.Wait("Long first step", durationMs = 200),
                        PlanStep.UserConfirmation("Second", "Continue?")
                    )
                )
            )
        }
        delay(30)
        h.engine.cancel()
        val result = deferred.await()

        assertFalse(result.success)
        assertEquals("Cancelled", result.failureReason)
        assertEquals(1, result.completedSteps)
        assertTrue(h.engine.getCurrentPlan() != null)
    }

    @Test
    fun execute_remainingStepKinds_failGracefully() = runBlocking {
        val h = harness()
        val steps = listOf(
            PlanStep.NativeAction("Help", "HELP"),
            PlanStep.AccessibilityAction("Tap", "tap missing"),
            PlanStep.MemoryRetrieval("Lookup", "missing fact"),
            PlanStep.OcrAction("Read", "full"),
            PlanStep.LmReasoning("Reason", "explain this"),
            PlanStep.VisionAction("Inspect", "what is here?"),
            PlanStep.Retry("Retry", "missing-step")
        )

        assertTrue(h.engine.execute(ExecutionPlan(goal = "native", steps = listOf(steps[0]))).success)
        for (step in steps.drop(1)) {
            val result = h.engine.execute(ExecutionPlan(goal = step.description, steps = listOf(step)))
            if (step is PlanStep.VisionAction) {
                assertTrue("Vision fallback should complete with a context", result.success)
            } else {
                assertFalse("${step.description} should fail safely", result.success)
            }
        }
    }

    @Test
    fun learningRecorder_recordsSuccessFailureAndCapabilityHistory() = runBlocking {
        val h = harness()
        assertTrue(h.memory.initialize())
        val recorder = LearningRecorder(h.memory)
        val successPlan = ExecutionPlan(goal = "remember this", goalSummary = "QUERY", steps = emptyList())
        val failurePlan = ExecutionPlan(goal = "remember this failed", goalSummary = "QUERY", steps = emptyList())
        val success = ExecutionResult(successPlan.id, success = true, totalSteps = 1, completedSteps = 1)
        val failure = ExecutionResult(
            failurePlan.id,
            success = false,
            totalSteps = 1,
            completedSteps = 0,
            failedStepIndex = 0,
            failureReason = "No memory found"
        )

        recorder.recordExecution(success, successPlan)
        recorder.recordExecution(failure, failurePlan)
        recorder.recordCapabilityFailure("Accessibility Service", "tap button", "not found")
        recorder.recordCapabilityFailure("Accessibility Service", "tap button", "timeout")
        recorder.recordCapabilitySuccess("Accessibility Service", "tap button")
        recorder.recordUserCorrection("open the app", "open settings instead")

        val stats = recorder.getStats()
        assertEquals(2, stats.totalPlansExecuted)
        assertEquals(1, stats.successfulPlans)
        assertEquals(1, stats.failedPlans)
        assertEquals(1, stats.capabilitiesWithFailures)
        assertEquals(1, stats.capabilitiesWithSuccesses)
        assertEquals(0.5f, stats.successRate, 0.001f)
        assertTrue(recorder.getCapabilitySuccessRate("Accessibility Service") in 0f..1f)
        assertNotNull(h.memory.retrieve("plan_failure_${failurePlan.id.take(8)}"))
        assertEquals(MemoryCategory.LEARNED_FACT, h.memory.retrieve("plan_exec_${failurePlan.id.take(8)}")?.category)
    }
}
