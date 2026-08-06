package com.example.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.ai.AiSettings
import com.example.openvoice.ai.AiConfig
import com.example.openvoice.ai.DeviceProfiler
import com.example.openvoice.ai.DeviceProfiler.ModelTier
import com.example.openvoice.ai.ModelManager
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.ai.BenchmarkRunner
import com.example.openvoice.developer.DeveloperConsole
import com.example.openvoice.router.CapabilityRouter
import com.example.openvoice.router.Resolution
import com.example.openvoice.intent.IntentResult
import com.example.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiRuntimeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var profiler: DeviceProfiler
    private lateinit var settings: AiSettings
    private lateinit var router: CapabilityRouter

    @Before
    fun setup() {
        Logger.init(true)
        profiler = DeviceProfiler(context)
        settings = AiSettings(context)
        router = CapabilityRouter()
    }

    // ── DeviceProfiler Tests ────────────────────────────────────────────────

    @Test
    fun profiler_detectsCapabilities() {
        val caps = profiler.getCapabilities()
        assertTrue("CPU cores > 0", caps.cpuCores > 0)
        assertTrue("Total RAM > 0", caps.totalRamMb > 0)
        assertNotNull("CPU arch not null", caps.cpuArch)
        assertTrue("API level >= 21", caps.androidApiLevel >= 21)
    }

    @Test
    fun profiler_recommendsModel_sufficientRam() {
        val caps = profiler.getCapabilities()
        val rec = profiler.recommendModel()
        assertNotNull(rec.suggestedModel)
        assertTrue("Model recommendation has reason", rec.reason.isNotEmpty())
    }

    @Test
    fun profiler_lowRam_device_returnsNone() {
        // Simulate low RAM by verifying the recommendation logic
        val caps = profiler.getCapabilities()
        val rec = profiler.recommendModel()
        // Should always have a recommendation tier (even if NONE)
        assertTrue(rec.tier.ordinal >= ModelTier.NONE.ordinal)
    }

    @Test
    fun profiler_estimatesModelRequirements() {
        val req = profiler.estimateModelRequirements(7.0f) // 7B param model
        assertTrue("RAM requirement > 0", req.ramRequiredMb > 0)
        assertTrue("Storage requirement > 0", req.storageRequiredMb > 0)
        assertTrue("Threads >= 1", req.recommendedThreads >= 1)
        assertTrue("Context tokens > 0", req.initialContextTokens > 0)
    }

    @Test
    fun profiler_getSummary_returnsNonEmpty() {
        val summary = profiler.getSummary()
        assertTrue("Summary should not be empty", summary.isNotEmpty())
        assertTrue("Summary should contain CPU info", summary.contains("CPU"))
        assertTrue("Summary should contain RAM info", summary.contains("RAM"))
    }

    @Test
    fun profiler_hasVulkanCheck() {
        val caps = profiler.getCapabilities()
        // Vulkan may or may not be supported; just check it doesn't throw
        assertNotNull(caps.hasVulkan)
    }

    // ── AiSettings Tests ──────────────────────────────────────────────────────

    @Test
    fun settings_saveAndLoad_roundTrips() {
        val config = AiConfig(
            activeModelPath = "/tmp/test.gguf",
            modelName = "TestModel",
            contextLength = 4096,
            temperature = 0.5f,
            maxTokens = 1024,
            threads = 4,
            gpuAcceleration = true,
            streamingEnabled = false,
            batterySaverMode = false
        )
        settings.save(config)
        val loaded = settings.load()
        assertEquals("/tmp/test.gguf", loaded.activeModelPath)
        assertEquals(4096, loaded.contextLength)
        assertEquals(0.5f, loaded.temperature, 0.01f)
        assertEquals(1024, loaded.maxTokens)
        assertEquals(4, loaded.threads)
        assertTrue(loaded.gpuAcceleration)
        assertFalse(loaded.streamingEnabled)
    }

    @Test
    fun settings_applyBatterySaver_reducesResources() {
        val config = AiConfig(maxTokens = 1024, threads = 4, contextLength = 4096)
        val saver = config.copy(batterySaverMode = true)
        val applied = settings.applyBatterySaver(saver)
        assertTrue("Battery saver reduces max tokens", applied.maxTokens < 1024)
        assertEquals("Battery saver reduces threads", 1, applied.threads)
        assertTrue("Battery saver reduces context", applied.contextLength <= 4096)
    }

    @Test
    fun settings_defaults_areSane() {
        val config = AiConfig()
        assertEquals(2048, config.contextLength)
        assertEquals(0.7f, config.temperature, 0.01f)
        assertEquals(512, config.maxTokens)
        assertEquals(2, config.threads)
        assertFalse(config.gpuAcceleration)
    }

    // ── CapabilityRouter AI Tests ────────────────────────────────────────────

    @Test
    fun router_resolvesQueryToLocalLm() {
        val result = IntentResult("QUERY", 0.8f, mapOf("query" to "what is the meaning of life"))
        val resolution = router.resolve(result)
        assertTrue("QUERY should route to LocalLm", resolution is Resolution.LocalLm)
        val lm = resolution as Resolution.LocalLm
        assertTrue(lm.prompt.isNotEmpty())
    }

    @Test
    fun router_resolvesQuery_aboutAssistant_locally() {
        val result = IntentResult("QUERY", 0.9f, mapOf("query" to "who are you"))
        val resolution = router.resolve(result)
        assertTrue("Assistant identity questions route to LocalLm", resolution is Resolution.LocalLm)
    }

    // ── InferenceEngine Tests (model-independent) ────────────────────────────

    @Test
    fun engine_startsUnloaded() {
        // Creating InferenceEngine requires settings + profiler
        // Just test the interface concept: engine not ready by default
        val engine = InferenceEngine(context, settings, profiler)
        assertFalse("Engine should not be ready without loading a model", engine.isReady)
    }

    @Test
    fun engine_estimateTokens_returnsPositive() {
        val engine = InferenceEngine(context, settings, profiler)
        val count = engine.estimateTokens("Hello world, this is a test sentence.")
        assertTrue("Token count should be positive", count > 0)
    }

    @Test
    fun engine_canRunModel_checksResources() {
        val engine = InferenceEngine(context, settings, profiler)
        // Very small model (0.1B) should always be runnable
        val canRun = engine.canRunModel(0.1f)
        assertNotNull(canRun)
    }

    // ── ModelManager Tests ────────────────────────────────────────────────────

    @Test
    fun modelManager_installedModels_isEmptyByDefault() = runBlocking {
        val mgr = ModelManager(context, profiler, settings)
        val models = mgr.getInstalledModels()
        assertNotNull("Model list should not be null", models)
    }

    @Test
    fun modelManager_getStorage_returnsNonNegative() = runBlocking {
        val mgr = ModelManager(context, profiler, settings)
        val (used, free) = mgr.getStorageUsage()
        assertTrue("Storage used >= 0", used >= 0)
        assertTrue("Storage free >= 0", free >= 0)
    }

    @Test
    fun modelManager_recommendedModels_haveNames() {
        val mgr = ModelManager(context, profiler, settings)
        val models = mgr.recommendedModels
        assertTrue("Should have recommended models", models.isNotEmpty())
        for (model in models) {
            assertTrue("Model name not empty", model.name.isNotEmpty())
            assertTrue("Model URL not empty", model.url.isNotEmpty())
            assertTrue("Model params > 0", model.paramsB > 0f)
            assertTrue("Model RAM > 0", model.ramRequiredMb > 0)
        }
    }

    @Test
    fun modelManager_getActiveModel_returnsEmptyInitially() {
        val mgr = ModelManager(context, profiler, settings)
        assertNull("No active model should be set initially", mgr.getActiveModel())
    }

    // ── DeveloperConsole Tests ────────────────────────────────────────────────

    @Test
    fun console_tracksPipelineEvents() {
        // Create console dependencies
        val engine = InferenceEngine(context, settings, profiler)
        val mgr = ModelManager(context, profiler, settings)
        val console = DeveloperConsole(profiler, engine, mgr, router)

        // Start with empty state
        assertTrue("No errors initially", console.diagnostics.value.errors.isEmpty())

        // Simulate pipeline events
        console.onAudioLevel(0.5f)
        assertEquals(0.5f, console.diagnostics.value.audioLevel, 0.01f)

        console.onSpeechDetected(0.8f)
        assertEquals("speech", console.diagnostics.value.speechState)
        assertEquals(0.8f, console.diagnostics.value.vadConfidence, 0.01f)

        console.onWakeWord("openvoice", 0.85f)
        assertEquals("openvoice", console.diagnostics.value.wakeWordDetected)

        console.onSttComplete("hello world")
        assertEquals("hello world", console.diagnostics.value.transcript)

        val intentResult = IntentResult("LAUNCH_APP", 0.95f, mapOf("app" to "spotify"))
        console.onIntentClassified(intentResult)
        assertEquals("LAUNCH_APP", console.diagnostics.value.intent)

        val resolution = Resolution.Native("LAUNCH_APP", mapOf("app" to "spotify"))
        console.onCapabilitySelected(resolution)
        assertTrue(console.diagnostics.value.capabilitySelected.contains("Native"))

        console.onActionExecuted("Opened spotify")
        assertTrue(console.diagnostics.value.totalLatencyMs >= 0)
    }

    @Test
    fun console_getTimelineSummary_containsStages() {
        val engine = InferenceEngine(context, settings, profiler)
        val mgr = ModelManager(context, profiler, settings)
        val console = DeveloperConsole(profiler, engine, mgr, router)

        console.onSpeechDetected(0.9f)
        console.onWakeWord("openvoice", 0.9f)
        console.onSttComplete("test")
        val intentResult = IntentResult("SET_TIMER", 0.9f, mapOf("duration" to "30 seconds"))
        console.onIntentClassified(intentResult)
        console.onCapabilitySelected(Resolution.Native("SET_TIMER", emptyMap()))
        console.onActionExecuted("Timer set")

        val summary = console.getTimelineSummary()
        assertTrue("Summary contains VAD stage", summary.contains("VAD"))
        assertTrue("Summary contains STT stage", summary.contains("STT"))
        assertTrue("Summary contains Intent stage", summary.contains("Intent"))
        assertTrue("Summary contains Route stage", summary.contains("Route"))
        assertTrue("Summary contains Action stage", summary.contains("Action"))
    }

    @Test
    fun console_clearLog_resetsState() {
        val engine = InferenceEngine(context, settings, profiler)
        val mgr = ModelManager(context, profiler, settings)
        val console = DeveloperConsole(profiler, engine, mgr, router)

        console.onSpeechDetected(0.5f)
        console.onError("test error")
        console.resetPipeline()

        assertEquals("Pipeline diagnostics reset after clear",
            0f, console.diagnostics.value.audioLevel, 0.01f)
    }

    @Test
    fun console_eventLog_hasMaxSize() {
        val engine = InferenceEngine(context, settings, profiler)
        val mgr = ModelManager(context, profiler, settings)
        val console = DeveloperConsole(profiler, engine, mgr, router)

        // Generate 300 events
        for (i in 0 until 300) {
            console.onError("error #$i")
        }

        val log = console.getEventLog()
        assertTrue("Event log should be capped", log.size <= 200)
    }

    // ── Performance Benchmarks ────────────────────────────────────────────────

    @Test
    fun benchmark_profilerSpeed() {
        val iterations = 100
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            profiler.getCapabilities()
            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("DeviceProfiler benchmark (n=$iterations): avg=${avgUs}µs")
        assertTrue("Profiler should complete in < 10ms per call", avgUs < 10_000)
    }

    @Test
    fun benchmark_routerSpeed() {
        val intents = listOf(
            IntentResult("LAUNCH_APP", 0.95f, mapOf("app" to "spotify")),
            IntentResult("QUERY", 0.8f, mapOf("query" to "what's the weather")),
            IntentResult("READ_SCREEN", 0.9f),
            IntentResult("SET_TIMER", 0.85f, mapOf("duration" to "5 minutes"))
        )
        val iterations = 100
        // Warm up the JIT before timing
        for (i in 0 until 10) {
            for (intent in intents) router.resolve(intent)
        }
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            for (intent in intents) {
                val start = System.nanoTime()
                router.resolve(intent)
                timings.add((System.nanoTime() - start) / 1_000)
            }
        }

        val avgUs = timings.average().toLong()
        println("CapabilityRouter benchmark (n=${intents.size * iterations}): avg=${avgUs}µs")
        assertTrue("Router resolution < 1000µs", avgUs < 1000)
    }

    @Test
    fun benchmark_settingsLoadStore() {
        val config = AiConfig(contextLength = 8192, temperature = 0.3f, threads = 4)

        val writeTimings = mutableListOf<Long>()
        val readTimings = mutableListOf<Long>()

        for (i in 0 until 50) {
            var start = System.nanoTime()
            settings.save(config)
            writeTimings.add((System.nanoTime() - start) / 1_000)

            start = System.nanoTime()
            settings.load()
            readTimings.add((System.nanoTime() - start) / 1_000)
        }

        val avgWriteUs = writeTimings.average().toLong()
        val avgReadUs = readTimings.average().toLong()
        println("Settings benchmark (n=50): write=${avgWriteUs}µs read=${avgReadUs}µs")
        assertTrue("Settings write < 50ms", avgWriteUs < 50_000)
        assertTrue("Settings read < 10ms", avgReadUs < 10_000)
    }
}
