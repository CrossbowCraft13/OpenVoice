package com.example.openvoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.ai.AiConfig
import com.example.openvoice.ai.AiSettings
import com.example.openvoice.ai.DeviceProfiler
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.ai.InferenceResult
import com.example.openvoice.ai.ModelManager
import com.example.openvoice.memory.KnowledgeGraph
import com.example.openvoice.memory.Relation
import com.example.openvoice.perception.OcrEngine
import com.example.openvoice.perception.OcrResult
import com.example.openvoice.perception.PerceptionEngine
import com.example.openvoice.perception.PerceptionSource
import com.example.openvoice.perception.ScreenshotPipeline
import com.example.openvoice.perception.TextBlock
import com.example.openvoice.perception.VisualMemoryCache
import com.example.openvoice.perception.vision.VisionRuntime
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
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
import java.net.ServerSocket

/**
 * Coverage pass 3d — the final push toward the 80% roadmap gate.
 *
 * Uses small test seams (captureOverride / fallbackResult / confidenceOverride)
 * to drive code paths that otherwise need platform consent or a real model:
 * the full capture → OCR pipeline, the vision fusion path, and the live
 * download/checksum logic (against a loopback HTTP server, permitted for debug
 * builds only via app/src/debug network security config).
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureSeamTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    @Test
    fun captureFullScreen_usesOverride() = runBlocking {
        val pipeline = ScreenshotPipeline(context)
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        pipeline.captureOverride = { bitmap }
        try {
            val result = pipeline.captureFullScreen()
            assertNotNull(result)
            val captured = result!!
            assertTrue(captured.bitmap === bitmap)
            assertTrue(captured.width > 0)          // from real screen metrics
            assertTrue(captured.height > 0)
            assertTrue(captured.captureTimeMs >= 0)
        } finally {
            pipeline.captureOverride = null
        }
    }

    @Test
    fun captureFullScreen_overrideReturningNull_returnsNull() = runBlocking {
        val pipeline = ScreenshotPipeline(context)
        pipeline.captureOverride = { null }
        try {
            assertNull(pipeline.captureFullScreen())
        } finally {
            pipeline.captureOverride = null
        }
    }

    @Test
    fun captureRegion_cropsFromOverride() = runBlocking {
        val pipeline = ScreenshotPipeline(context)
        pipeline.captureOverride = { Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) }
        try {
            val region = pipeline.captureRegion(Rect(10, 10, 60, 60))
            assertNotNull(region)
            val cropped = region!!
            assertEquals(50, cropped.width)
            assertEquals(50, cropped.height)
        } finally {
            pipeline.captureOverride = null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
//  PerceptionEngine — the full OCR and vision paths via the seams
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class PerceptionPipelineSeamTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Harness(
        val engine: PerceptionEngine,
        val cache: VisualMemoryCache,
        val blackboard: TaskBlackboard,
        val pipeline: ScreenshotPipeline,
        val vision: VisionRuntime
    )

    private fun build(): Harness {
        val settings = AiSettings(context)
        val profiler = DeviceProfiler(context)
        val inference = InferenceEngine(context, settings, profiler)
        val pipeline = ScreenshotPipeline(context)
        val ocr = OcrEngine(context)
        val vision = VisionRuntime(inference)
        val cache = VisualMemoryCache()
        val blackboard = TaskBlackboard()
        return Harness(
            PerceptionEngine(pipeline, ocr, vision, cache, blackboard),
            cache, blackboard, pipeline, vision
        )
    }

    private fun bitmap() = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888)

    @Before
    fun setup() {
        Logger.init(true)
    }

    @Test
    fun perceive_ocrPath_withRealCapture() = runBlocking {
        val h = build()
        h.blackboard.setCurrentApp("com.pipe")
        h.pipeline.captureOverride = { bitmap() }
        try {
            val (ctx, stats) = h.engine.perceive()
            // Capture ran, OCR ran on the real bitmap, screenshot + OCR were cached.
            // NOTE: asserts rely on the screenshot cache TTL (2s) — safe because the
            // cache lookups run immediately after perceive() in the same test.
            assertNotNull(ctx.screenshot)
            assertNotNull(h.cache.getCachedOcr("com.pipe", ""))
            assertNotNull(h.cache.getCachedScreenshot("com.pipe", ""))
            assertEquals(PerceptionSource.OCR, stats.sourceUsed)
        } finally {
            h.pipeline.captureOverride = null
        }
    }

    @Test
    fun perceive_visionPath_withOverrides() = runBlocking {
        val h = build()
        h.blackboard.setCurrentApp("com.vision")
        h.pipeline.captureOverride = { bitmap() }
        h.vision.fallbackResult = "A bar chart showing weekly sales"
        h.vision.confidenceOverride = 0.74f
        try {
            val (ctx, stats) = h.engine.perceive()
            assertTrue(ctx.fullText.contains("bar chart"))
            assertTrue(ctx.textBlocks.any { it.text.contains("bar chart") })
            assertEquals(0.74f, ctx.sourceConfidence[PerceptionSource.VISION] ?: 0f, 0.01f)
            assertNotNull(h.cache.getCachedVision("com.vision", ""))
            assertEquals(PerceptionSource.VISION, stats.sourceUsed)
        } finally {
            h.pipeline.captureOverride = null
            h.vision.fallbackResult = null
            h.vision.confidenceOverride = null
        }
    }

    @Test
    fun answerQuestion_usesVisionTier() = runBlocking {
        val h = build()
        h.blackboard.setCurrentApp("com.question")
        h.pipeline.captureOverride = { bitmap() }
        h.vision.fallbackResult = "A pie chart"
        h.vision.confidenceOverride = 0.74f
        try {
            h.engine.perceive() // populates lastScreenContext with a screenshot
            val answer = h.engine.answerQuestion("what is this")
            assertEquals(PerceptionSource.VISION, answer.source)
            assertEquals(0.74f, answer.confidence, 0.01f)
            assertTrue(answer.answer.contains("A pie chart"))
        } finally {
            h.pipeline.captureOverride = null
            h.vision.fallbackResult = null
            h.vision.confidenceOverride = null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
//  VisionRuntime — fallback-driven success behaviors
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class VisionRuntimeSeamTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun vision(): VisionRuntime {
        val settings = AiSettings(context)
        val profiler = DeviceProfiler(context)
        return VisionRuntime(InferenceEngine(context, settings, profiler))
    }

    @Test
    fun describe_withFallback_returnsIt() = runBlocking {
        val vision = vision()
        vision.fallbackResult = "Screen shows a login form"
        try {
            val result = vision.describe(ByteArray(4))
            assertEquals("Screen shows a login form", result.description)
            assertEquals(0.74f, result.confidence, 0.01f)
            assertTrue(result.latencyMs >= 0)
        } finally {
            vision.fallbackResult = null
        }
    }

    @Test
    fun identifyElements_parsesVisionOutput() = runBlocking {
        val vision = vision()
        vision.fallbackResult = "There is a \"Search\" button at the top.\n" +
            "A \"Menu\" icon at the bottom.\n" +
            "A \"Profile\" image on the left."
        try {
            val elements = vision.identifyElements(ByteArray(4))
            assertEquals(3, elements.size)
            assertTrue(elements.any { it.label == "Search" && it.category == "button" })
            assertTrue(elements.any { it.label == "Menu" && it.category == "icon" })
            assertTrue(elements.any { it.label == "Profile" && it.category == "image" })
            assertTrue(elements.all { it.description.isNotEmpty() })
        } finally {
            vision.fallbackResult = null
        }
    }

    @Test
    fun buildVisionPrompt_formatsChatTemplate() {
        val prompt = vision().buildVisionPrompt("Describe this screen", ByteArray(4))
        assertTrue(prompt.contains("<|im_start|>user"))
        assertTrue(prompt.contains("<image>"))
        assertTrue(prompt.contains("Describe this screen"))
        assertTrue(prompt.contains("<|im_start|>assistant"))
    }

    @Test
    fun estimateConfidence_respectsOverride() {
        val vision = vision()
        assertEquals(0f, vision.estimateConfidence(), 0f)
        vision.confidenceOverride = 0.74f
        assertEquals(0.74f, vision.estimateConfidence(), 0.01f)
        vision.confidenceOverride = null
        assertEquals(0f, vision.estimateConfidence(), 0f)
    }
}

// ─────────────────────────────────────────────────────────────────────
//  ModelManager.downloadModel — real streaming against a loopback server
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class ModelManagerDownloadTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val modelDir: File get() = File(context.filesDir, "models").also { it.mkdirs() }
    private lateinit var settings: AiSettings

    @Before
    fun setup() {
        Logger.init(true)
        settings = AiSettings(context)
        settings.save(AiConfig())
    }

    @After
    fun cleanup() {
        modelDir.listFiles()?.filter { it.name.startsWith("covtest") }?.forEach { it.delete() }
        settings.save(AiConfig())
    }

    private fun manager() = ModelManager(context, DeviceProfiler(context), settings)

    private fun rec(name: String, port: Int, sha256: String = "") =
        ModelManager.RecommendedModel(
            name = name,
            url = "http://127.0.0.1:$port/model.gguf",
            sha256 = sha256,
            paramsB = 0.1f,
            ramRequiredMb = 128,
            fileSizeMb = 1
        )

    /** Serves one response on a fresh ephemeral port in a background thread. */
    private fun serveOnce(
        body: ByteArray,
        contentLength: Long = body.size.toLong()
    ): ServerSocket {
        val server = ServerSocket(0)
        thread {
            try {
                val socket = server.accept()
                socket.getInputStream().read(ByteArray(4096)) // consume the request
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/octet-stream\r\n")
                    if (contentLength >= 0) append("Content-Length: $contentLength\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                val out = socket.getOutputStream()
                out.write(header.toByteArray())
                out.write(body)
                out.flush()
                socket.close()
            } catch (_: Exception) {
                // client disconnected or server closed — fine for a one-shot stub
            } finally {
                server.close()
            }
        }
        return server
    }

    @Test
    fun downloadModel_successViaLocalServer() = runBlocking {
        val body = "fake-gguf-model-bytes-0123456789".toByteArray()
        val server = serveOnce(body)
        val progress = mutableListOf<Float>()
        try {
            assertTrue(manager().downloadModel(rec("covtest-http-ok", server.localPort)) { progress.add(it) })
            val file = File(modelDir, "covtest-http-ok.gguf")
            assertTrue(file.exists())
            assertEquals(body.size.toLong(), file.length())
            assertTrue(progress.isNotEmpty())
            assertEquals(1f, progress.last(), 0.01f)
        } finally {
            server.close()
        }
    }

    @Test
    fun downloadModel_shaMismatch_deletesFile() = runBlocking {
        val server = serveOnce("not-the-right-hash-content".toByteArray())
        try {
            val wrongSha = "0".repeat(64) // deliberately wrong checksum
            assertFalse(manager().downloadModel(rec("covtest-http-sha", server.localPort, wrongSha)))
            assertFalse(File(modelDir, "covtest-http-sha.gguf").exists())
        } finally {
            server.close()
        }
    }

    @Test
    fun downloadModel_connectionFailure_returnsFalse() = runBlocking {
        // Ephemeral port that is immediately freed → connection refused
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(manager().downloadModel(rec("covtest-http-bad", port)))
        assertFalse(File(modelDir, "covtest-http-bad.gguf").exists())
    }

    @Test
    fun downloadModel_withoutContentLength_stillSucceeds() = runBlocking {
        val server = serveOnce("streaming-bytes".toByteArray(), contentLength = -1)
        val progress = mutableListOf<Float>()
        try {
            assertTrue(manager().downloadModel(rec("covtest-http-nolen", server.localPort)) { progress.add(it) })
            assertTrue(File(modelDir, "covtest-http-nolen.gguf").exists())
            // No Content-Length → no mid-stream throttled progress, only final 1f
            assertEquals(1f, progress.last(), 0.01f)
        } finally {
            server.close()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
//  Remaining small helpers
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class KnowledgeGraphHelpersTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    @Test
    fun findBySubjectAndPredicate_andDeleteRelation() = runBlocking {
        context.deleteDatabase("openvoice_knowledge.db")
        val graph = KnowledgeGraph(context)
        graph.initialize()
        graph.store(Relation(subject = "S1", predicate = "likes", obj = "X"))
        graph.store(Relation(subject = "S2", predicate = "likes", obj = "Y"))
        graph.store(Relation(subject = "S1", predicate = "hates", obj = "Z"))

        assertEquals(2, graph.findBySubject("S1").size)
        assertEquals(2, graph.findByPredicate("likes").size)
        assertEquals(listOf("X"), graph.find("S1", "likes"))
        assertEquals(3, graph.count())

        val first = graph.findBySubject("S1")[0]
        assertTrue(graph.deleteRelation(first.id))
        assertEquals(1, graph.findBySubject("S1").size)
    }
}

@RunWith(AndroidJUnit4::class)
class InferenceResultCoverageTest {

    @Test
    fun sealedVariants_construct() {
        val success = InferenceResult.Success("hello", 12.5f, 42)
        assertEquals("hello", success.text)
        assertEquals(42, success.totalTokens)
        assertEquals(12.5f, success.tokensPerSecond, 0.01f)

        assertEquals("partial", InferenceResult.StreamProgress("partial").partial)
        assertEquals("boom", InferenceResult.Error("boom").message)
        assertTrue(InferenceResult.Cancelled is InferenceResult)
        assertTrue(InferenceResult.ModelNotLoaded is InferenceResult)
    }
}

// ─────────────────────────────────────────────────────────────────────
//  OcrEngine region capture + VisualMemoryCache invalidation
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class OcrRegionAndCacheTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun recognizeRegion_cropsAndRecognizes() = runBlocking {
        val ocr = OcrEngine(context)
        val result = ocr.recognizeRegion(bitmap(100, 100), Rect(10, 10, 60, 60))
        assertNotNull(result)
        assertTrue(result.latencyMs >= 0)
        assertTrue(ocr.isAvailable())
    }

    @Test
    fun recognizeRegion_outOfBounds_returnsEmpty() = runBlocking {
        val ocr = OcrEngine(context)
        val result = ocr.recognizeRegion(bitmap(50, 50), Rect(0, 0, 200, 200))
        assertNotNull(result)
        assertTrue(result.textBlocks.isEmpty())
    }

    @Test
    fun visualCache_invalidateForAppChange_andStats() {
        val cache = VisualMemoryCache()
        cache.cacheOcr("com.a", "Act", OcrResult(listOf(TextBlock("x", Rect(0, 0, 1, 1))), "x"))
        assertTrue(cache.hasValidOcr("com.a", "Act"))

        cache.invalidateForAppChange("com.b", "Other")
        val stats = cache.getStats()
        assertTrue(stats.contains("OCR cache"))
        assertTrue(stats.contains("Vision cache"))
        assertTrue(stats.contains("Last invalidation"))

        cache.invalidateAll()
        assertFalse(cache.hasValidOcr("com.a", "Act"))
    }
}
