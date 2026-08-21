package io.github.crossbowcraft13.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.crossbowcraft13.openvoice.ai.AiSettings
import io.github.crossbowcraft13.openvoice.ai.DeviceProfiler
import io.github.crossbowcraft13.openvoice.ai.InferenceEngine
import io.github.crossbowcraft13.openvoice.intent.IntentClassifier
import io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph
import io.github.crossbowcraft13.openvoice.memory.MemoryEngine
import io.github.crossbowcraft13.openvoice.memory.MemoryLifecycle
import io.github.crossbowcraft13.openvoice.memory.VectorStore
import io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption
import io.github.crossbowcraft13.openvoice.operator.OperatorRegistry
import io.github.crossbowcraft13.openvoice.perception.OcrEngine
import io.github.crossbowcraft13.openvoice.perception.PerceptionEngine
import io.github.crossbowcraft13.openvoice.perception.ScreenshotPipeline
import io.github.crossbowcraft13.openvoice.perception.VisualMemoryCache
import io.github.crossbowcraft13.openvoice.perception.vision.VisionRuntime
import io.github.crossbowcraft13.openvoice.planner.CostModel
import io.github.crossbowcraft13.openvoice.planner.PlanStep
import io.github.crossbowcraft13.openvoice.planner.Planner
import io.github.crossbowcraft13.openvoice.planner.ExecutionPlan
import io.github.crossbowcraft13.openvoice.task.TaskBlackboard
import io.github.crossbowcraft13.openvoice.util.Logger
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
