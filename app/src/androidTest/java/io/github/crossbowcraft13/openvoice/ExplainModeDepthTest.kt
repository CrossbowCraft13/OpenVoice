package io.github.crossbowcraft13.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.crossbowcraft13.openvoice.ai.AiSettings
import io.github.crossbowcraft13.openvoice.ai.DeviceProfiler
import io.github.crossbowcraft13.openvoice.ai.InferenceEngine
import io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph
import io.github.crossbowcraft13.openvoice.memory.MemoryEngine
import io.github.crossbowcraft13.openvoice.memory.MemoryLifecycle
import io.github.crossbowcraft13.openvoice.memory.VectorStore
import io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption
import io.github.crossbowcraft13.openvoice.planner.ExecutionPlan
import io.github.crossbowcraft13.openvoice.planner.ExecutionResult
import io.github.crossbowcraft13.openvoice.planner.PlanStep
import io.github.crossbowcraft13.openvoice.system.explain.ExplainMode
import io.github.crossbowcraft13.openvoice.task.TaskBlackboard
import io.github.crossbowcraft13.openvoice.util.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExplainModeDepthTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
        context.deleteDatabase("openvoice_memories.db")
        context.deleteDatabase("openvoice_knowledge.db")
    }

    private fun explain(blackboard: TaskBlackboard): ExplainMode {
        val settings = AiSettings(context)
        val profiler = DeviceProfiler(context)
        val inference = InferenceEngine(context, settings, profiler)
        val vector = VectorStore(context, inference)
        val graph = KnowledgeGraph(context)
        val encryption = MemoryEncryption(context)
        val lifecycle = MemoryLifecycle(context, inference, encryption, vector, graph)
        val memory = MemoryEngine(context, vector, graph, encryption, lifecycle, blackboard)
        return ExplainMode(blackboard, memory)
    }

    @Test
    fun fullExplanation_coversEveryStepKindAndFailureFormatting() {
        val blackboard = TaskBlackboard()
        blackboard.startNewTask("complete workflow", "complete workflow")
        blackboard.setPlan(listOf("native", "vision", "wait"))
        val mode = explain(blackboard)
        val steps = listOf(
            PlanStep.NativeAction("open", "HELP"),
            PlanStep.AccessibilityAction("tap", "tap ok"),
            PlanStep.MemoryRetrieval("lookup", "fact"),
            PlanStep.OcrAction("read"),
            PlanStep.LmReasoning("reason", "why"),
            PlanStep.VisionAction("inspect"),
            PlanStep.UserConfirmation("confirm", "continue?"),
            PlanStep.PluginAction("plugin", "id", "run"),
            PlanStep.Conditional("condition", "true", emptyList()),
            PlanStep.Loop("loop", listOf("a"), emptyList()),
            PlanStep.Wait("wait", 0),
            PlanStep.Retry("retry", "step")
        )
        val plan = ExecutionPlan("explain-plan", "complete workflow", "workflow", steps,
            overallConfidence = 0.82f)
        steps.forEach { mode.recordDecision(it, "test rationale", listOf("alternate path")) }
        mode.recordExecution(plan, ExecutionResult(
            planId = plan.id,
            success = false,
            failedStepIndex = 2,
            failureReason = "lookup failed",
            totalLatencyMs = 1234
        ))

        val explanation = mode.explainLast()
        assertEquals(12, explanation.steps.size)
        assertTrue(explanation.capabilitiesUsed.contains("VisionAction"))
        assertTrue(explanation.capabilitiesNotUsed.isEmpty())
        assertEquals("Higher", explanation.batteryImpact)
        assertEquals(1, explanation.failures.size)
        assertTrue(mode.explainDecision("what happened").contains("Goal"))
        assertTrue(mode.explainDecision("how confident are you").contains("82%"))
        assertTrue(mode.explainDecision("how long did it take").contains("1234ms"))
        assertTrue(mode.explainDecision("what happens next").contains("Next steps"))
        assertTrue(mode.explainDecision("why did you tap that").contains("test rationale"))
        assertTrue(mode.explainDecision("why did you open that").contains("test rationale"))
        assertTrue(mode.explainDecision("why did you use ai").contains("LLM"))
        assertTrue(mode.explainDecision("why did you use memory").contains("Memory"))
        assertTrue(mode.explainDecision("why did you use vision").contains("Vision"))
        assertTrue(mode.explainDecision("why did you use the llm").contains("LLM"))
        assertTrue(mode.explainDecision("why did you use accessibility").contains("Accessibility"))
        assertTrue(mode.explainDecision("not vision").contains("capabilities"))
        assertTrue(mode.explainDecision("are you sure").contains("confidence"))
        assertTrue(mode.explainDecision("why did you do that").contains("Goal"))

        // A sparse plan exercises the formatted "available but not needed" section.
        val sparse = ExecutionPlan(goal = "sparse", steps = listOf(PlanStep.NativeAction("open", "HELP")))
        mode.recordExecution(sparse, ExecutionResult(sparse.id, success = true, totalLatencyMs = 5))
        assertTrue(mode.explainDecision("unrelated question").contains("not needed"))

        blackboard.markCompleted()
        blackboard.setPlan(emptyList())
        assertTrue(mode.explainDecision("what next").contains("No pending steps"))
        mode.clearLog()
        assertEquals(0, mode.explainLast().decisions.size)
    }

    @Test
    fun emptyExplanation_hasNoBatteryImpact() {
        val mode = explain(TaskBlackboard())
        val explanation = mode.explainLast()
        assertFalse(explanation.steps.isNotEmpty())
        assertEquals("None", explanation.batteryImpact)
        assertTrue(mode.explainDecision("unrelated question").contains("Goal"))
    }
}
