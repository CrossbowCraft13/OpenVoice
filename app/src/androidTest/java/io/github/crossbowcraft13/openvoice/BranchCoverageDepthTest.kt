package io.github.crossbowcraft13.openvoice

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.crossbowcraft13.openvoice.ai.AiSettings
import io.github.crossbowcraft13.openvoice.ai.DeviceProfiler
import io.github.crossbowcraft13.openvoice.ai.InferenceEngine
import io.github.crossbowcraft13.openvoice.developer.DeveloperConsole
import io.github.crossbowcraft13.openvoice.intent.IntentResult
import io.github.crossbowcraft13.openvoice.operator.OperatorRegistry
import io.github.crossbowcraft13.openvoice.router.CapabilityRouter
import io.github.crossbowcraft13.openvoice.router.Resolution
import io.github.crossbowcraft13.openvoice.system.ResourceManager
import io.github.crossbowcraft13.openvoice.system.ResourceManager.PerformanceTier
import io.github.crossbowcraft13.openvoice.system.ResourceManager.ResourceState
import io.github.crossbowcraft13.openvoice.ai.DeviceProfiler.ThermalStatus
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperatorBranchCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var registry: OperatorRegistry
    private lateinit var throwingContext: Context

    @Before
    fun setup() {
        Logger.init(true)
        registry = OperatorRegistry()
        throwingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                throw IllegalStateException("activity blocked for coverage")
            }
        }
    }

    @Test
    fun operatorGuardsAndContextFreeCommands() = runBlocking {
        assertFalse(registry.exec("UNKNOWN", null, emptyMap()).success)
        assertFalse(registry.exec("LAUNCH_APP", null, emptyMap()).success)
        assertFalse(registry.exec("SEND_SMS", null, emptyMap()).success)
        assertFalse(registry.exec("SEND_SMS", null, mapOf("contact" to "555")).success)
        assertFalse(registry.exec("MAKE_CALL", null, emptyMap()).success)
        assertFalse(registry.exec("SET_TIMER", null, emptyMap()).success)
        assertFalse(registry.exec("SET_ALARM", null, emptyMap()).success)
        assertTrue(registry.exec("HELP", null, emptyMap()).success)
        assertTrue(registry.exec("STOP", null, emptyMap()).success)
        assertTrue(registry.exec("QUERY", null, emptyMap()).success)
    }

    @Test
    fun operatorParsersCoverAlternateInputs() = runBlocking {
        // Null context deliberately stops after parsing and avoids launching apps.
        for (duration in listOf("2 hours 5 minutes", "30 seconds", "7", "no duration")) {
            assertFalse(registry.exec("SET_TIMER", null, mapOf("duration" to duration)).success)
        }
        for (time in listOf("7:30 PM", "12:05 AM", "25:00", "7:60", "no time")) {
            assertFalse(registry.exec("SET_ALARM", null, mapOf("time" to time)).success)
        }

        // Exercise every settings selector while making startActivity fail safely.
        for (target in listOf("wifi", "bluetooth", "sound", "display", "apps", "battery", "main", "other")) {
            assertFalse(registry.exec("OPEN_SETTINGS", throwingContext, mapOf("target" to target)).success)
        }

        // Exercise numeric, named, fallback, and directional volume branches.
        for (level in listOf("25%", "max", "minimum", "half", "unrecognized")) {
            registry.exec("ADJUST_VOLUME", context, mapOf("level" to level))
        }
        for (direction in listOf("up", "down", "mute", "unmute", "other")) {
            registry.exec("ADJUST_VOLUME", context, mapOf("direction" to direction))
        }
        assertFalse(registry.exec("ADJUST_VOLUME", context, emptyMap()).success)

        // Cover alternate entity keys and graceful failures from privileged APIs.
        assertFalse(registry.exec("SEND_SMS", throwingContext,
            mapOf("phone" to "555", "text" to "hello")).success)
        assertFalse(registry.exec("MAKE_CALL", null, mapOf("phone" to "555")).success)
        assertFalse(registry.exec("LAUNCH_APP", null, mapOf("app" to "unlisted-app")).success)
    }
}

@RunWith(AndroidJUnit4::class)
class ResourceManagerBranchCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun manager(state: ResourceState): ResourceManager =
        ResourceManager(context, DeviceProfiler(context)).also {
            it.resourceStateOverride = { state }
        }

    @Test
    fun everyPerformanceTierAndConfigIsReachable() = runBlocking {
        val cases = listOf(
            ResourceState(batteryPercent = 5, thermalState = ThermalStatus.COOL) to PerformanceTier.MINIMUM,
            ResourceState(batteryPercent = 80, thermalState = ThermalStatus.CRITICAL) to PerformanceTier.MINIMUM,
            ResourceState(batteryPercent = 20, thermalState = ThermalStatus.COOL) to PerformanceTier.POWER_SAVE,
            ResourceState(batteryPercent = 80, thermalState = ThermalStatus.WARM) to PerformanceTier.POWER_SAVE,
            ResourceState(batteryPercent = 80, thermalState = ThermalStatus.HOT) to PerformanceTier.POWER_SAVE,
            ResourceState(batteryPercent = 80, isCharging = true, thermalState = ThermalStatus.COOL,
                availableRamMb = 4096) to PerformanceTier.MAXIMUM,
            ResourceState(batteryPercent = 80, isCharging = false, thermalState = ThermalStatus.COOL,
                availableRamMb = 4096, cpuLoadPercent = 0.3f) to PerformanceTier.HIGH,
            ResourceState(batteryPercent = 80, isCharging = false, thermalState = ThermalStatus.COOL,
                availableRamMb = 512, cpuLoadPercent = 0.8f) to PerformanceTier.BALANCED
        )

        for ((state, expected) in cases) {
            val manager = manager(state)
            assertEquals(expected, manager.getPerformanceTier())
            assertEquals(expected, manager.getPerformanceTier())
            assertTrue(manager.getPowerSummary().contains(expected.name))
            val config = manager.getRecommendedConfig()
            assertTrue(config.maxTokens > 0)
            assertEquals(expected != PerformanceTier.MINIMUM && expected != PerformanceTier.POWER_SAVE,
                manager.canRunHeavyInference())
        }
    }
}

@RunWith(AndroidJUnit4::class)
class DeveloperConsoleBranchCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun console(): DeveloperConsole {
        val profiler = DeviceProfiler(context)
        val engine = InferenceEngine(context, AiSettings(context), profiler)
        return DeveloperConsole(profiler, engine,
            io.github.crossbowcraft13.openvoice.ai.ModelManager(context, profiler, AiSettings(context)),
            CapabilityRouter())
    }

    @Before
    fun setup() {
        Logger.init(true)
    }

    @Test
    fun recordsAllCapabilityAndTimelineBranches() {
        val console = console()
        console.onAudioLevel(0.5f)
        console.onSpeechDetected(0.8f)
        console.onWakeWord("hey openvoice", 0.9f)
        console.onSttStart()
        console.onSttComplete("open settings")
        console.onIntentClassified(IntentResult("OPEN_SETTINGS", 0.9f))
        console.onActionExecuted("opened")
        console.onPerceptionComplete("OCR", 10, 2, 3, 1, 4, 5, "com.example.app", false)
        console.onError("test error")

        val resolutions = listOf<Resolution>(
            Resolution.Native("HELP", emptyMap()),
            Resolution.Accessibility(listOf("back")),
            Resolution.LocalLm("what is this"),
            Resolution.Vision("describe"),
            Resolution.Planner("finish task"),
            Resolution.Unsupported("not supported")
        )
        resolutions.forEach(console::onCapabilitySelected)

        val summary = console.getTimelineSummary()
        assertTrue(summary.contains("Audio"))
        assertTrue(summary.contains("Wake"))
        assertTrue(summary.contains("STT"))
        assertTrue(summary.contains("Intent"))
        assertTrue(summary.contains("Route"))
        assertTrue(summary.contains("Action"))
        assertTrue(console.diagnostics.value.errors.contains("test error"))
        assertEquals(6, console.diagnostics.value.routerDecisions.size)

        console.refreshModelDiagnostics()
        assertTrue(console.getEventLog().isNotEmpty())
        console.resetPipeline()
        assertTrue(console.getTimelineSummary().contains("Audio: 0%"))
        console.clearLog()
        assertTrue(console.getEventLog().isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
class TaskBlackboardBranchCoverageTest {

    private fun node() = io.github.crossbowcraft13.openvoice.accessibility.UiNode(
        text = "Button",
        description = null,
        className = "android.widget.Button",
        packageName = "com.example.app",
        viewId = "button",
        bounds = android.graphics.Rect(0, 0, 10, 10),
        isClickable = true,
        isEditable = false,
        isScrollable = false,
        isChecked = false,
        isCheckable = false,
        isFocusable = true,
        isEnabled = true,
        isPassword = false,
        isVisible = true,
        depth = 0,
        children = emptyList()
    )

    @Test
    fun accessibilityCompletionAndTimelineBranches() {
        val board = io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
        assertFalse(board.requiresCurrentScreen())
        assertFalse(board.canCompleteWithA11y())
        assertTrue(board.getTimeline().contains("Route: —"))

        board.startNewTask("tap this button", "tap this button")
        assertTrue(board.requiresCurrentScreen())
        for (goal in listOf("screen", "here", "current", "click", "press", "button")) {
            board.startNewTask(goal, goal)
            assertTrue(board.requiresCurrentScreen())
        }
        board.startNewTask("tap this button", "tap this button")
        board.setCurrentApp("com.example.app")
        board.setCurrentActivity("MainActivity")
        board.setCurrentScreen("main")
        board.setKeyboardVisible(true)
        board.setActiveDialogs(listOf("Confirm"))
        board.setUiTree(node())
        assertTrue(board.canCompleteWithA11y())

        board.setVisionResults(listOf("chart"))
        assertFalse(board.canCompleteWithA11y())
        board.setVisionResults(emptyList())
        board.setIntent(
            IntentResult("OPEN_SETTINGS", 0.9f),
            Resolution.Native("OPEN_SETTINGS", emptyMap())
        )
        board.setMemoryHits(listOf("preference"))
        board.setOcrText("Button")
        board.setPlan(listOf("tap", "confirm"))
        assertEquals("tap", board.currentPlanStep())
        assertTrue(board.advancePlan())
        assertEquals("confirm", board.currentPlanStep())

        board.recordSuccess("tap", 12)
        board.recordFailure("confirm", "blocked", "retry", 20)
        val failedTimeline = board.getTimeline()
        assertTrue(failedTimeline.contains("Route: Native: OPEN_SETTINGS"))
        assertTrue(failedTimeline.contains("✗ confirm"))
        assertTrue(board.canCompleteWithA11y())

        board.setIntent(IntentResult("QUERY", 0.9f), Resolution.LocalLm("explain"))
        assertTrue(board.getTimeline().contains("LocalLm"))
        board.setIntent(IntentResult("NAVIGATE_BACK", 0.9f), Resolution.Accessibility(listOf("back")))
        assertTrue(board.getTimeline().contains("Accessibility"))

        board.markCompleted()
        assertFalse(board.advancePlan())
        assertTrue(board.state.value.planComplete)
        assertFalse(board.canCompleteWithA11y())

        board.markFailed("failure")
        assertEquals(io.github.crossbowcraft13.openvoice.task.CompletionStatus.FAILED, board.state.value.completionStatus)
        board.markCancelled()
        assertEquals(io.github.crossbowcraft13.openvoice.task.CompletionStatus.CANCELLED, board.state.value.completionStatus)
        board.reset()
        assertFalse(board.requiresCurrentScreen())
    }
}
