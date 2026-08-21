package io.github.crossbowcraft13.openvoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.crossbowcraft13.openvoice.ai.AiConfig
import io.github.crossbowcraft13.openvoice.ai.AiSettings
import io.github.crossbowcraft13.openvoice.ai.DeviceProfiler
import io.github.crossbowcraft13.openvoice.ai.InferenceBackend
import io.github.crossbowcraft13.openvoice.ai.InferenceEngine
import io.github.crossbowcraft13.openvoice.memory.KnowledgeGraph
import io.github.crossbowcraft13.openvoice.memory.Relation
import io.github.crossbowcraft13.openvoice.perception.OcrEngine
import io.github.crossbowcraft13.openvoice.perception.OcrResult
import io.github.crossbowcraft13.openvoice.perception.ScreenContext
import io.github.crossbowcraft13.openvoice.perception.TextBlock
import io.github.crossbowcraft13.openvoice.perception.VisualMemoryCache
import io.github.crossbowcraft13.openvoice.perception.VisionResult
import io.github.crossbowcraft13.openvoice.perception.vision.VisionRuntime
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Deeper branch coverage for the local knowledge graph. */
@RunWith(AndroidJUnit4::class)
class KnowledgeGraphDepthTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: KnowledgeGraph

    @Before
    fun setup() {
        Logger.init(true)
        context.deleteDatabase("openvoice_knowledge.db")
        graph = KnowledgeGraph(context)
    }

    @After
    fun cleanup() {
        context.deleteDatabase("openvoice_knowledge.db")
    }

    @Test
    fun uninitializedQueries_failSafe() = runBlocking {
        assertFalse(graph.store(Relation(subject = "a", predicate = "p", obj = "b")))
        assertTrue(graph.findBySubject("a").isEmpty())
        assertTrue(graph.findByPredicate("p").isEmpty())
        assertTrue(graph.find("a", "p").isEmpty())
        assertTrue(graph.findRelated("a").isEmpty())
        assertTrue(graph.findPath("a", "b").isEmpty())
        assertTrue(graph.getSubgraph("a").isEmpty())
        assertTrue(graph.search().isEmpty())
        assertTrue(graph.exportAll().isEmpty())
        assertEquals(0L, graph.count())
    }

    @Test
    fun bulkImportSearchAndTraversal_coverAlternatePaths() = runBlocking {
        assertTrue(graph.initialize())
        val relations = listOf(
            Relation(subject = "A", predicate = "likes", obj = "B"),
            Relation(subject = "B", predicate = "uses", obj = "C"),
            Relation(subject = "C", predicate = "owns", obj = "D"),
            Relation(subject = "X", predicate = "likes", obj = "Y")
        )
        assertTrue(graph.storeAll(relations))
        assertEquals(4L, graph.count())
        assertEquals(1, graph.find("A", "likes").size)
        assertEquals(2, graph.findByPredicate("likes").size)
        assertEquals(2, graph.findRelated("C").size)
        assertEquals(1, graph.search(predicate = "likes", limit = 1).size)
        assertTrue(graph.findPath("A", "D", maxDepth = 3).isNotEmpty())
        assertTrue(graph.findPath("A", "missing").isEmpty())
        assertTrue(graph.getSubgraph("A", depth = 0).isEmpty())
        assertTrue(graph.getSubgraph("A", depth = 3).size >= 3)

        val exported = graph.exportAll()
        assertEquals(4, exported.size)
        assertTrue(graph.clear())
        assertEquals(0L, graph.count())
        assertTrue(graph.importAll(exported))
        assertEquals(4L, graph.count())
        assertTrue(graph.deleteSubject("C"))
        assertEquals(2L, graph.count())
        assertTrue(graph.delete("A", "likes", "B"))
        assertEquals(1L, graph.count())
        assertTrue(graph.deleteRelation("missing-id"))
    }
}

/** AI configuration and JVM-safe profiler branches. */
@RunWith(AndroidJUnit4::class)
class AiConfigurationDepthTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: AiSettings

    @Before
    fun setup() {
        Logger.init(true)
        settings = AiSettings(context)
        settings.save(AiConfig())
    }

    @After
    fun cleanup() {
        settings.save(AiConfig())
    }

    @Test
    fun advancedSettings_roundTripPreservesAllPersistedValues() {
        val expected = AiConfig(
            activeModelPath = "/models/test.gguf",
            modelName = "test",
            contextLength = 4096,
            temperature = 0.2f,
            maxTokens = 256,
            threads = 6,
            gpuAcceleration = true,
            streamingEnabled = false,
            batterySaverMode = true,
            topK = 12,
            topP = 0.8f,
            repeatPenalty = 1.3f,
            frequencyPenalty = 0.25f,
            presencePenalty = 0.4f,
            stopTokens = listOf("<stop>", "END"),
            embeddingsEnabled = false,
            autoDownloadModels = false,
            maxStorageGb = 3
        )
        settings.save(expected)
        val actual = settings.load()

        assertEquals(expected.activeModelPath, actual.activeModelPath)
        assertEquals(expected.modelName, actual.modelName)
        assertEquals(expected.contextLength, actual.contextLength)
        assertEquals(expected.temperature, actual.temperature, 0.001f)
        assertEquals(expected.maxTokens, actual.maxTokens)
        assertEquals(expected.threads, actual.threads)
        assertEquals(expected.gpuAcceleration, actual.gpuAcceleration)
        assertEquals(expected.streamingEnabled, actual.streamingEnabled)
        assertEquals(expected.batterySaverMode, actual.batterySaverMode)
        assertEquals(expected.topK, actual.topK)
        assertEquals(expected.topP, actual.topP, 0.001f)
        assertEquals(expected.repeatPenalty, actual.repeatPenalty, 0.001f)
        assertEquals(expected.frequencyPenalty, actual.frequencyPenalty, 0.001f)
        assertEquals(expected.presencePenalty, actual.presencePenalty, 0.001f)
        assertEquals(expected.stopTokens.toSet(), actual.stopTokens.toSet())
        assertEquals(expected.embeddingsEnabled, actual.embeddingsEnabled)
        assertEquals(expected.autoDownloadModels, actual.autoDownloadModels)
        assertEquals(expected.maxStorageGb, actual.maxStorageGb)
    }

    @Test
    fun profiler_withoutAndroidContext_returnsSafeNoneRecommendation() {
        val profiler = DeviceProfiler(null)
        val capabilities = profiler.getCapabilities()
        assertEquals(0L, capabilities.totalRamMb)
        assertEquals(0L, capabilities.availableRamMb)
        assertEquals(DeviceProfiler.ThermalStatus.UNKNOWN, capabilities.thermalStatus)
        assertEquals(DeviceProfiler.ModelTier.NONE, profiler.recommendModel().tier)
        assertTrue(profiler.getSummary().contains("Recommended: none"))
    }

    @Test
    fun batterySaver_clampsSmallTokenBudgets() {
        val applied = settings.applyBatterySaver(
            AiConfig(maxTokens = 1, contextLength = 4096, batterySaverMode = true)
        )
        assertEquals(128, applied.maxTokens)
        assertEquals(1, applied.threads)
        assertEquals(1024, applied.contextLength)
    }
}

/** Cache, OCR-region, and loaded-vision branches that do not need cloud/model assets. */
@RunWith(AndroidJUnit4::class)
class PerceptionDepthTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    @Test
    fun visualCache_handlesOcrVisionScreenshotAndContextTogether() {
        val cache = VisualMemoryCache()
        val ocr = OcrResult(
            textBlocks = listOf(TextBlock("Hello", Rect(0, 0, 20, 20))),
            fullText = "Hello",
            confidence = 0.9f
        )
        val vision = VisionResult(description = "A screen", confidence = 0.74f)
        val screen = ScreenContext(packageName = "com.test", fullText = "Hello")

        cache.cacheOcr("com.test", "Main", ocr)
        cache.cacheVision("com.test", "Main", vision)
        cache.cacheScreenshot("com.test", "Main", byteArrayOf(1, 2, 3))
        cache.cacheScreenContext("com.test", "Main", screen)

        assertEquals(ocr, cache.getCachedOcr("com.test", "Main"))
        assertEquals(vision, cache.getCachedVision("com.test", "Main"))
        assertEquals(3, cache.getCachedScreenshot("com.test", "Main")?.size)
        assertEquals(screen, cache.getCachedScreenContext("com.test", "Main"))
        assertTrue(cache.hasValidOcr("com.test", "Main"))
        assertTrue(cache.hasValidVision("com.test", "Main"))
        assertFalse(cache.isStale(System.currentTimeMillis()))
        assertTrue(cache.isStale(System.currentTimeMillis() - VisualMemoryCache.SCREENSHOT_TTL_MS - 1))

        cache.invalidateAll()
        assertNull(cache.getCachedOcr("com.test", "Main"))
        assertNull(cache.getCachedVision("com.test", "Main"))
        assertNull(cache.getCachedScreenshot("com.test", "Main"))
        assertNull(cache.getCachedScreenContext("com.test", "Main"))
    }

    @Test
    fun ocrRegion_coversValidAndInvalidCropPaths() = runBlocking {
        val engine = OcrEngine(context)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val valid = engine.recognizeRegion(bitmap, Rect(0, 0, 50, 50))
        assertNotNull(valid)
        assertTrue(valid.latencyMs >= 0)

        val invalid = engine.recognizeRegion(bitmap, Rect(90, 90, 80, 80))
        assertTrue(invalid.textBlocks.isEmpty())
        assertEquals("", invalid.fullText)
    }

    @Test
    fun loadedVision_coversInferenceAndElementParsing() = runBlocking {
        val settings = AiSettings(context)
        settings.save(AiConfig())
        val backend = CoverageBackend()
        val engine = InferenceEngine(context, settings, DeviceProfiler(context), backend)
        val model = File(context.filesDir, "coverage-depth-model.gguf").apply { writeBytes(ByteArray(8)) }
        try {
            assertTrue(engine.loadModelFromPath(model.absolutePath))
            val vision = VisionRuntime(engine)
            backend.completeResult = "A \"Search\" button at the top."

            val described = vision.describe(byteArrayOf(1))
            assertEquals("A \"Search\" button at the top.", described.description)
            assertEquals(0.74f, described.confidence, 0.001f)
            val elements = vision.identifyElements(byteArrayOf(1))
            assertEquals(1, elements.size)
            assertEquals("Search", elements[0].label)
            assertEquals("button", elements[0].category)
            assertTrue(vision.answerQuestion(byteArrayOf(1), "what is here?").isNotEmpty())
        } finally {
            engine.unloadModel()
            model.delete()
        }
    }
}

private class CoverageBackend : InferenceBackend {
    var loaded = false
    var completeResult = ""

    override fun isLoaded() = loaded
    override fun isProcessing() = false
    override fun loadModel(modelFile: File, config: AiConfig): Boolean {
        loaded = true
        return true
    }
    override fun complete(prompt: String, config: AiConfig): String = completeResult
    override fun completeStream(prompt: String, config: AiConfig, onToken: (String) -> Unit): String = completeResult
    override fun embed(text: String): FloatArray? = null
    override fun cancel() = Unit
    override fun getMetadata(): Map<String, String> = emptyMap()
    override fun estimateTokenCount(text: String): Int = text.length / 4
    override fun resetContext() = Unit
    override fun release() {
        loaded = false
    }
}
