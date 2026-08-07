package com.example.openvoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.ai.AiSettings
import com.example.openvoice.ai.DeviceProfiler
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.perception.OcrEngine
import com.example.openvoice.perception.OcrResult
import com.example.openvoice.perception.PerceptionEngine
import com.example.openvoice.perception.PerceptionSource
import com.example.openvoice.perception.ScreenContext
import com.example.openvoice.perception.ScreenshotPipeline
import com.example.openvoice.perception.TextBlock
import com.example.openvoice.perception.VisualMemoryCache
import com.example.openvoice.perception.vision.VisionRuntime
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Coverage pass 3c — perception package.
 *
 * Drives PerceptionEngine.perceive() through the no-a11y / cache / OCR-fusion
 * paths on-device, and exercises ScreenshotPipeline + VisionRuntime fallbacks
 * (the vision model itself is unavailable, so fallback branches are the code).
 */
@RunWith(AndroidJUnit4::class)
class PerceptionEngineCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Harness(
        val engine: PerceptionEngine,
        val cache: VisualMemoryCache,
        val blackboard: TaskBlackboard
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
            cache, blackboard
        )
    }

    private val ocrBlock = OcrResult(
        textBlocks = listOf(TextBlock("Settings", Rect(0, 0, 10, 10), confidence = 0.9f)),
        fullText = "Settings",
        latencyMs = 1,
        confidence = 0.9f
    )

    @Before
    fun setup() {
        Logger.init(true)
    }

    @Test
    fun perceive_noA11y_noCache_buildsEmptyContext() = runBlocking {
        val h = build()
        val (ctx, stats) = h.engine.perceive()
        assertNotNull(ctx)
        assertEquals("", ctx.packageName)
        assertEquals(0, ctx.elements.size)
        assertFalse(stats.cacheHit)
        assertEquals(PerceptionSource.ACCESSIBILITY, stats.sourceUsed)
        assertTrue(stats.totalLatencyMs >= 0)
        assertEquals(0, stats.elementsFused)
    }

    @Test
    fun perceive_cacheHit_shortCircuits() = runBlocking {
        val h = build()
        h.blackboard.setCurrentApp("com.cache")
        val cachedCtx = ScreenContext(packageName = "com.cache", fullText = "cached text")
        h.cache.cacheScreenContext("com.cache", "", cachedCtx)
        h.cache.cacheOcr("com.cache", "", ocrBlock)
        val (ctx, stats) = h.engine.perceive()
        assertTrue(stats.cacheHit)
        assertTrue(ctx.fullText.contains("cached text"))
    }

    @Test
    fun perceive_ocrPath_fusesCachedOcr() = runBlocking {
        val h = build()
        h.blackboard.setCurrentApp("com.app")
        h.cache.cacheOcr("com.app", "", ocrBlock)
        val (ctx, stats) = h.engine.perceive()
        assertFalse(stats.cacheHit)
        // OCR blocks are fused into the context
        assertTrue(ctx.textBlocks.any { it.text == "Settings" })
        assertTrue(ctx.fullText.contains("Settings"))
        // Source confidence map includes OCR
        assertEquals(0.9f, ctx.sourceConfidence[PerceptionSource.OCR] ?: 0f, 0.01f)
        // OCR text is pushed to the blackboard
        assertTrue(h.blackboard.state.value.ocrText.contains("Settings"))
        // Primary source reflects the OCR contribution
        assertEquals(PerceptionSource.OCR, stats.sourceUsed)
    }

    @Test
    fun perceive_forceRefresh_detectsNoChange() = runBlocking {
        val h = build()
        h.blackboard.setCurrentApp("com.stable")
        val (first, _) = h.engine.perceive()
        assertTrue(first.changedSinceLastCapture) // no previous capture → changed

        val (second, _) = h.engine.perceive(forceRefresh = true)
        assertFalse(second.changedSinceLastCapture) // same screen → unchanged
    }

    @Test
    fun getLastContext_populatedAfterPerceive() = runBlocking {
        val h = build()
        assertNull(h.engine.getLastContext())
        h.blackboard.setCurrentApp("com.last")
        h.engine.perceive()
        assertNotNull(h.engine.getLastContext())
    }

    @Test
    fun answerQuestion_noContext_returnsNotice() = runBlocking {
        val h = build()
        val answer = h.engine.answerQuestion("what is on screen?")
        assertTrue(answer.answer.contains("No screen context"))
        assertEquals(PerceptionSource.VISION, answer.source)
        assertEquals(0f, answer.confidence, 0f)
    }

    @Test
    fun answerQuestion_whatApp_usesA11yTier() = runBlocking {
        val h = build()
        h.blackboard.setCurrentApp("com.answer.app")
        h.engine.perceive()
        val answer = h.engine.answerQuestion("what app is this?")
        assertTrue(answer.answer.contains("com.answer.app"))
        assertEquals(PerceptionSource.ACCESSIBILITY, answer.source)
        assertEquals(0.99f, answer.confidence, 0.01f)
    }

    @Test
    fun answerQuestion_findText_usesOcrTier() = runBlocking {
        val h = build()
        h.blackboard.setCurrentApp("com.ocr")
        h.cache.cacheOcr("com.ocr", "", ocrBlock)
        h.engine.perceive()
        val answer = h.engine.answerQuestion("find Settings")
        assertTrue(answer.answer.contains("Found"))
        assertTrue(answer.answer.contains("Settings"))
        assertEquals(PerceptionSource.OCR, answer.source)
    }

    @Test
    fun answerQuestion_fallthrough_returnsFallback() = runBlocking {
        val h = build()
        h.blackboard.setCurrentApp("com.fallback")
        h.engine.perceive()
        val answer = h.engine.answerQuestion("hello there")
        assertTrue(answer.answer.contains("don't have enough information"))
        assertEquals(PerceptionSource.OCR, answer.source)
        assertEquals(0.5f, answer.confidence, 0.01f)
    }
}

// ─────────────────────────────────────────────────────────────────────
//  ScreenshotPipeline — capture fallbacks + image utilities
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class ScreenshotPipelineCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun pipeline() = ScreenshotPipeline(context)

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun captureFullScreen_placeholder_returnsNull() = runBlocking {
        // No MediaProjection consent on device → capture degrades to null
        assertNull(pipeline().captureFullScreen())
    }

    @Test
    fun captureRegion_returnsNullWhenNoCapture() = runBlocking {
        assertNull(pipeline().captureRegion(Rect(0, 0, 10, 10)))
    }

    @Test
    fun downsample_largeBitmap_scalesDown() {
        val result = pipeline().downsample(bitmap(1000, 800), maxDimension = 512)
        assertTrue(result.width <= 512)
        assertTrue(result.height <= 512)
    }

    @Test
    fun downsample_smallBitmap_returnsOriginal() {
        val small = bitmap(100, 100)
        assertSame(small, pipeline().downsample(small, maxDimension = 512))
    }

    @Test
    fun encodeToJpeg_returnsNonEmpty() {
        val bytes = pipeline().encodeToJpeg(bitmap(50, 50), quality = 80)
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun getScreenMetrics_returnsPositiveDimensions() {
        val metrics = pipeline().getScreenMetrics()
        assertTrue(metrics.widthPixels > 0)
        assertTrue(metrics.heightPixels > 0)
    }

    @Test
    fun hasDisplayCutout_doesNotThrow() {
        // Emulator may or may not have a cutout; must not crash either way
        assertNotNull(pipeline().hasDisplayCutout())
    }

    @Test
    fun toVisionInput_encodesDownsampledJpeg() {
        val capture = ScreenshotPipeline.CaptureResult(
            bitmap = bitmap(600, 400),
            width = 600,
            height = 400,
            rotation = 0,
            captureTimeMs = 5
        )
        val bytes = pipeline().toVisionInput(capture)
        assertTrue(bytes.isNotEmpty())
    }
}

// ─────────────────────────────────────────────────────────────────────
//  VisionRuntime — fallback behavior without a loaded multimodal model
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class VisionRuntimeCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun vision(): VisionRuntime {
        val settings = AiSettings(context)
        val profiler = DeviceProfiler(context)
        val inference = InferenceEngine(context, settings, profiler)
        return VisionRuntime(inference)
    }

    @Test
    fun describe_notReady_returnsFallback() = runBlocking {
        val result = vision().describe(ByteArray(4), "describe this")
        assertTrue(result.description.contains("not available"))
        assertEquals(0f, result.confidence, 0f)
        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun identifyElements_notReady_returnsEmpty() = runBlocking {
        assertTrue(vision().identifyElements(ByteArray(4)).isEmpty())
    }

    @Test
    fun answerQuestion_returnsDescription() = runBlocking {
        val answer = vision().answerQuestion(ByteArray(4), "what is here?")
        assertTrue(answer.isNotEmpty())
    }

    @Test
    fun detectChanges_returnsStaticMessage() = runBlocking {
        assertEquals(
            "Change detection requires side-by-side vision model comparison",
            vision().detectChanges(ByteArray(2), ByteArray(3))
        )
    }

    @Test
    fun estimateConfidence_zeroWhenNotReady() {
        assertEquals(0f, vision().estimateConfidence(), 0f)
    }

    @Test
    fun encodeImage_returnsJpeg() {
        val bitmap = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888)
        val bytes = vision().encodeImage(bitmap)
        assertTrue(bytes.isNotEmpty())
    }
}
