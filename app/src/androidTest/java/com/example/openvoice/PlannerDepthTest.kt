package com.example.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.ai.AiSettings
import com.example.openvoice.ai.DeviceProfiler
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.intent.IntentClassifier
import com.example.openvoice.memory.KnowledgeGraph
import com.example.openvoice.memory.MemoryEngine
import com.example.openvoice.memory.MemoryLifecycle
import com.example.openvoice.memory.VectorStore
import com.example.openvoice.memory.encryption.MemoryEncryption
import com.example.openvoice.operator.OperatorRegistry
import com.example.openvoice.perception.OcrEngine
import com.example.openvoice.perception.PerceptionEngine
import com.example.openvoice.perception.ScreenshotPipeline
import com.example.openvoice.perception.VisualMemoryCache
import com.example.openvoice.perception.vision.VisionRuntime
import com.example.openvoice.planner.CostModel
import com.example.openvoice.planner.PlanStep
import com.example.openvoice.planner.Planner
import com.example.openvoice.planner.ExecutionPlan
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerDepthTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun planner(): Planner {
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
        return Planner(IntentClassifier(), CostModel(), OperatorRegistry(), perception, memory, blackboard)
    }

    @Test
    fun createPlan_routesEverySimpleIntentFamily() = runBlocking {
        val cases = listOf(
            "open Spotify" to PlanStep.NativeAction::class,
            "send a message to Alice saying hello" to PlanStep.UserConfirmation::class,
            "set a timer for 5 minutes" to PlanStep.NativeAction::class,
            "set an alarm for 7 am" to PlanStep.NativeAction::class,
            "set volume to 50%" to PlanStep.NativeAction::class,
            "scroll down" to PlanStep.AccessibilityAction::class,
            "what's on my screen" to PlanStep.VisionAction::class,
            "what is my favorite color" to PlanStep.MemoryRetrieval::class,
            "help" to PlanStep.NativeAction::class,
            "say something completely unrecognized" to PlanStep.LmReasoning::class
        )

        for ((request, firstType) in cases) {
            val plan = planner().createPlan(request)
            assertEquals(request, plan.goal)
            assertTrue("$request should produce steps", plan.steps.isNotEmpty())
            assertTrue("$request first step type", firstType.java.isInstance(plan.steps.first()))
            assertTrue(plan.overallConfidence in 0f..1f)
            assertTrue(plan.estimatedTotalLatencyMs >= 0)
        }
    }

    @Test
    fun createPlan_genericCompoundAndScreenContextRequests() = runBlocking {
        val compound = planner().createPlan("screenshot and then help")
        assertEquals(3, compound.steps.size)
        assertTrue(compound.steps[0] is PlanStep.AccessibilityAction)
        assertTrue(compound.steps[1] is PlanStep.LmReasoning)
        assertTrue(compound.steps[2] is PlanStep.NativeAction)

        val contextual = planner().createPlan("screenshot this")
        assertEquals(2, contextual.steps.size)
        assertTrue(contextual.steps[0] is PlanStep.AccessibilityAction)
        assertTrue(contextual.steps[1] is PlanStep.LmReasoning)
    }

    @Test
    fun estimateConfidence_scoresEveryPlanStepKind() {
        val plan = ExecutionPlan(
            goal = "all capabilities",
            steps = listOf(
                PlanStep.NativeAction("native", "HELP"),
                PlanStep.AccessibilityAction("a11y", "tap ok"),
                PlanStep.MemoryRetrieval("memory", "fact"),
                PlanStep.OcrAction("ocr"),
                PlanStep.LmReasoning("llm", "reason"),
                PlanStep.VisionAction("vision"),
                PlanStep.UserConfirmation("confirm", "continue?"),
                PlanStep.PluginAction("plugin", "id", "action"),
                PlanStep.Conditional("conditional", "true", emptyList()),
                PlanStep.Loop("loop", emptyList(), emptyList()),
                PlanStep.Wait("wait", 0),
                PlanStep.Retry("retry", "step")
            )
        )
        val confidence = planner().estimateConfidence(plan)
        assertTrue(confidence > 0f)
        assertTrue(confidence < 1f)
        assertEquals(0f, planner().estimateConfidence(plan.copy(steps = emptyList())), 0f)
    }

    @Test
    fun costModel_coversCapabilityPriorityAndAlternates() {
        val model = CostModel()
        assertTrue(model.selectCheapestCapability("launch the camera") is PlanStep.NativeAction)
        assertTrue(model.selectCheapestCapability("remember this preference") is PlanStep.MemoryRetrieval)
        assertTrue(model.selectCheapestCapability("content on screen") is PlanStep.OcrAction)
        assertTrue(model.selectCheapestCapability("explain quantum computing") is PlanStep.LmReasoning)
        assertTrue(model.selectCheapestCapability("inspect the chart", requiresVisualAnalysis = true) is PlanStep.VisionAction)
        assertTrue(model.selectCheapestCapability("unclassified operation") is PlanStep.LmReasoning)

        val steps = listOf(
            PlanStep.NativeAction("native", "HELP"),
            PlanStep.AccessibilityAction("a11y", "tap"),
            PlanStep.OcrAction("ocr"),
            PlanStep.MemoryRetrieval("memory", "q"),
            PlanStep.LmReasoning("llm", "q"),
            PlanStep.VisionAction("vision"),
            PlanStep.UserConfirmation("confirm", "q"),
            PlanStep.PluginAction("plugin", "p", "a"),
            PlanStep.Conditional("conditional", "true", emptyList()),
            PlanStep.Loop("loop", emptyList(), emptyList()),
            PlanStep.Wait("wait", 0),
            PlanStep.Retry("retry", "id")
        )
        assertFalse(model.canEscalateTo(1f, 1f))
        for (step in steps) model.suggestAlternate(step)
    }
}
