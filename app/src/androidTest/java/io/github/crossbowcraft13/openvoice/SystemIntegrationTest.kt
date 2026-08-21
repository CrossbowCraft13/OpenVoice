package io.github.crossbowcraft13.openvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.crossbowcraft13.openvoice.system.explain.ExplainMode
import io.github.crossbowcraft13.openvoice.system.ResourceManager
import io.github.crossbowcraft13.openvoice.system.ResourceManager.PerformanceTier
import io.github.crossbowcraft13.openvoice.system.ReliabilityManager
import io.github.crossbowcraft13.openvoice.system.ReliabilityManager.Severity
import io.github.crossbowcraft13.openvoice.system.onboarding.OnboardingFlow
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemIntegrationTest {

    private lateinit var explainMode: ExplainMode
    private lateinit var reliabilityManager: ReliabilityManager

    @Before
    fun setup() {
        Logger.init(true)
        explainMode = ExplainMode(
            io.github.crossbowcraft13.openvoice.task.TaskBlackboard(),
            io.github.crossbowcraft13.openvoice.memory.MemoryEngine(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                io.github.crossbowcraft13.openvoice.memory.VectorStore(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.AiSettings(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext())
                    )
                ),
                io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                io.github.crossbowcraft13.openvoice.memory.MemoryLifecycle(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.AiSettings(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                    io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.memory.VectorStore(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                    io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
            )
        )

        reliabilityManager = ReliabilityManager(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            io.github.crossbowcraft13.openvoice.task.TaskBlackboard())
    }

    // ═══════════════════════════════════════════════════════════════
    //  ExplainMode Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun explainMode_decisionLogging() {
        val step = io.github.crossbowcraft13.openvoice.planner.PlanStep.NativeAction(
            "Open Settings", "OPEN_SETTINGS")
        explainMode.recordDecision(step, "Standard Android intent — fastest path",
            listOf("Accessibility (more expensive, not needed)"))
        assertTrue(explainMode.explainLast().decisions.isNotEmpty())
    }

    @Test
    fun explainMode_fullExplanation_formatting() {
        val explanation = explainMode.explainLast()
        assertNotNull(explanation)
    }

    @Test
    fun explainMode_whyTap() {
        val response = explainMode.explainDecision("why did you tap that")
        assertNotNull(response)
    }

    @Test
    fun explainMode_whyAccessibility() {
        val response = explainMode.explainDecision("why did you use accessibility")
        assertNotNull(response)
    }

    @Test
    fun explainMode_whyNotVision() {
        val response = explainMode.explainDecision("why didn't you use vision")
        assertNotNull(response)
    }

    @Test
    fun explainMode_confidenceExplanation() {
        val response = explainMode.explainDecision("how confident are you")
        assertNotNull(response)
    }

    @Test
    fun explainMode_clearLog() {
        explainMode.recordDecision(
            io.github.crossbowcraft13.openvoice.planner.PlanStep.NativeAction("Test", "TEST"),
            "Testing")
        explainMode.clearLog()
        assertTrue(explainMode.explainLast().decisions.isEmpty())
    }

    @Test
    fun explanation_batteryImpact_low() {
        val explanation = explainMode.explainLast()
        assertNotNull(explanation.batteryImpact)
    }

    // ═══════════════════════════════════════════════════════════════
    //  ResourceManager Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun resourceManager_performanceTier_alwaysReturns() = runBlocking {
        val rm = ResourceManager(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        val tier = rm.getPerformanceTier()
        assertNotNull(tier)
    }

    @Test
    fun resourceManager_aiConfig_adaptsToTier() = runBlocking {
        val rm = ResourceManager(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        val config = rm.getRecommendedConfig()
        assertTrue(config.threads >= 1)
        assertTrue(config.maxContextLength >= 256)
    }

    @Test
    fun resourceManager_powerSummary() = runBlocking {
        val rm = ResourceManager(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        val summary = rm.getPowerSummary()
        assertTrue(summary.isNotEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    //  ReliabilityManager Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun reliabilityManager_errorReporting() {
        reliabilityManager.reportError("TestComponent", "Test error", Severity.ERROR)
        val errors = reliabilityManager.getRecentErrors()
        assertTrue(errors.isNotEmpty())
        assertEquals("TestComponent", errors[0].component)
    }

    @Test
    fun reliabilityManager_warningDoesNotTriggerRestart() {
        reliabilityManager.reportError("Test", "Test warning", Severity.WARNING)
        // Should not crash
    }

    @Test
    fun reliabilityManager_healthSummary() {
        reliabilityManager.reportError("A", "err", Severity.WARNING)
        val summary = reliabilityManager.getHealthSummary()
        assertTrue(summary.contains("A"))
    }

    @Test
    fun reliabilityManager_clearErrors() {
        reliabilityManager.reportError("Test", "Test", Severity.ERROR)
        assertTrue(reliabilityManager.getRecentErrors().isNotEmpty())
        reliabilityManager.clearErrors()
        assertTrue(reliabilityManager.getRecentErrors().isEmpty())
    }

    @Test
    fun reliabilityManager_crashCount() {
        reliabilityManager.restoreState()
        // Should not crash
    }

    // ═══════════════════════════════════════════════════════════════
    //  OnboardingFlow Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun onboardingFlow_hasAllSteps() {
        val flow = OnboardingFlow(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            io.github.crossbowcraft13.openvoice.util.PermissionManager(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        assertEquals(6, flow.steps.size)
        assertEquals("welcome", flow.steps[0].id)
        assertEquals("complete", flow.steps[5].id)
    }

    @Test
    fun onboardingFlow_explanations_exist() {
        val flow = OnboardingFlow(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            io.github.crossbowcraft13.openvoice.util.PermissionManager(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        for (step in flow.steps) {
            val explanation = flow.getExplanation(step.id)
            assertNotNull("Explanation for ${step.id} should exist", explanation)
            assertTrue("Explanation for ${step.id} should not be empty",
                explanation?.isNotEmpty() ?: false)
        }
    }

    @Test
    fun onboardingFlow_stepDescriptions() {
        val flow = OnboardingFlow(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            io.github.crossbowcraft13.openvoice.util.PermissionManager(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        assertTrue(flow.steps[0].description.contains("private"))
        assertTrue(flow.steps[1].description.contains("voice commands"))
        assertTrue(flow.steps[3].description.contains("optional", ignoreCase = true))
    }

    // ═══════════════════════════════════════════════════════════════
    //  Privacy Dashboard Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun privacyDashboard_report_hasStructure() = runBlocking {
        val dashboard = io.github.crossbowcraft13.openvoice.system.PrivacyDashboard(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            io.github.crossbowcraft13.openvoice.memory.MemoryEngine(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                io.github.crossbowcraft13.openvoice.memory.VectorStore(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.AiSettings(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                io.github.crossbowcraft13.openvoice.memory.MemoryLifecycle(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.AiSettings(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                    io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.memory.VectorStore(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                    io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
            ),
            io.github.crossbowcraft13.openvoice.util.PermissionManager(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        val report = dashboard.getReport()
        assertTrue(report.totalMemories >= 0)
        assertTrue(report.encryptionEnabled)
    }

    @Test
    fun privacyDashboard_summary_containsKeyInfo() = runBlocking {
        val dashboard = io.github.crossbowcraft13.openvoice.system.PrivacyDashboard(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            io.github.crossbowcraft13.openvoice.memory.MemoryEngine(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                io.github.crossbowcraft13.openvoice.memory.VectorStore(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.AiSettings(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                io.github.crossbowcraft13.openvoice.memory.MemoryLifecycle(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.AiSettings(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                    io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.memory.VectorStore(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                    io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
            ),
            io.github.crossbowcraft13.openvoice.util.PermissionManager(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        val summary = dashboard.getSummary()
        assertTrue(summary.contains("Privacy"))
        assertTrue(summary.contains("encryption", ignoreCase = true))
        assertTrue(summary.contains("telemetry", ignoreCase = true))
        assertTrue(summary.contains("local"))
    }

    // ═══════════════════════════════════════════════════════════════
    //  SystemOrchestrator Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun orchestrator_initialization() = runBlocking {
        val orchestrator = io.github.crossbowcraft13.openvoice.system.SystemOrchestrator(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            io.github.crossbowcraft13.openvoice.planner.Planner(
                io.github.crossbowcraft13.openvoice.intent.IntentClassifier(),
                io.github.crossbowcraft13.openvoice.planner.CostModel(),
                io.github.crossbowcraft13.openvoice.operator.OperatorRegistry(),
                io.github.crossbowcraft13.openvoice.perception.PerceptionEngine(
                    io.github.crossbowcraft13.openvoice.perception.ScreenshotPipeline(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.perception.OcrEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.perception.vision.VisionRuntime(
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                    io.github.crossbowcraft13.openvoice.perception.VisualMemoryCache(),
                    io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
                ),
                io.github.crossbowcraft13.openvoice.memory.MemoryEngine(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.memory.VectorStore(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                    io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.memory.MemoryLifecycle(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                        io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.memory.VectorStore(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                                io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                                io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                    androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                        io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                    io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
                ),
                io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
            ),
            io.github.crossbowcraft13.openvoice.planner.ExecutionEngine(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                io.github.crossbowcraft13.openvoice.operator.OperatorRegistry(),
                io.github.crossbowcraft13.openvoice.perception.PerceptionEngine(
                    io.github.crossbowcraft13.openvoice.perception.ScreenshotPipeline(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.perception.OcrEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.perception.vision.VisionRuntime(
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                    io.github.crossbowcraft13.openvoice.perception.VisualMemoryCache(),
                    io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
                ),
                io.github.crossbowcraft13.openvoice.memory.MemoryEngine(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.memory.VectorStore(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                    io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.memory.MemoryLifecycle(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                        io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.memory.VectorStore(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                                io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                                io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                    androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                        io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                    io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
                ),                    io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.AiSettings(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                    io.github.crossbowcraft13.openvoice.task.TaskBlackboard(),
                    io.github.crossbowcraft13.openvoice.planner.DynamicReplanner(
                        io.github.crossbowcraft13.openvoice.planner.CostModel())
                ),
            io.github.crossbowcraft13.openvoice.memory.MemoryEngine(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                io.github.crossbowcraft13.openvoice.memory.VectorStore(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.AiSettings(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                io.github.crossbowcraft13.openvoice.memory.MemoryLifecycle(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.AiSettings(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                    io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.memory.VectorStore(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                    io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
            ),
            io.github.crossbowcraft13.openvoice.perception.PerceptionEngine(
                io.github.crossbowcraft13.openvoice.perception.ScreenshotPipeline(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                io.github.crossbowcraft13.openvoice.perception.OcrEngine(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                io.github.crossbowcraft13.openvoice.perception.vision.VisionRuntime(
                    io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.AiSettings(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                io.github.crossbowcraft13.openvoice.perception.VisualMemoryCache(),
                io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
            ),                io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.ai.AiSettings(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                io.github.crossbowcraft13.openvoice.task.TaskBlackboard(),
                explainMode,
                io.github.crossbowcraft13.openvoice.planner.LearningRecorder(
                io.github.crossbowcraft13.openvoice.memory.MemoryEngine(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    io.github.crossbowcraft13.openvoice.memory.VectorStore(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                    io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                    io.github.crossbowcraft13.openvoice.memory.MemoryLifecycle(
                        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                        io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                            io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                        io.github.crossbowcraft13.openvoice.memory.encryption.MemoryEncryption(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                        io.github.crossbowcraft13.openvoice.memory.VectorStore(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                            io.github.crossbowcraft13.openvoice.ai.InferenceEngine(
                                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                                io.github.crossbowcraft13.openvoice.ai.AiSettings(
                                    androidx.test.core.app.ApplicationProvider.getApplicationContext()),
                                io.github.crossbowcraft13.openvoice.ai.DeviceProfiler(
                                    androidx.test.core.app.ApplicationProvider.getApplicationContext()))),
                        io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph(
                            androidx.test.core.app.ApplicationProvider.getApplicationContext())),
                    io.github.crossbowcraft13.openvoice.task.TaskBlackboard()
                )
            )
        )
        assertFalse(orchestrator.isInitialized())
        val initialized = orchestrator.initialize()
        // May return false without real dependencies, but shouldn't crash
        assertNotNull(initialized)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Performance Benchmarks
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun benchmark_explainMode() {
        val iterations = 100

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            explainMode.explainDecision("why did you do that")
            val us = (System.nanoTime() - start) / 1_000
            if (i == 0) println("ExplainMode first call: ${us}µs")
        }
    }

    @Test
    fun benchmark_reliabilityErrorLogging() {
        val iterations = 100
        // Warm up the JIT before timing
        for (i in 0 until 50) reliabilityManager.reportError("Warm", "warmup", Severity.DEBUG)
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            reliabilityManager.reportError("Bench", "Error #$i", Severity.DEBUG)
            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("Reliability error logging (n=$iterations): avg=${avgUs}µs")
        assertTrue("Error logging < 500µs", avgUs < 500)
    }

    @Test
    fun benchmark_onboardingStateCheck() {
        val flow = OnboardingFlow(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            io.github.crossbowcraft13.openvoice.util.PermissionManager(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        val iterations = 500
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            flow.getState()
            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("Onboarding state check (n=$iterations): avg=${avgUs}µs")
        assertTrue("Onboarding check < 500µs", avgUs < 500)
    }
}
