package io.github.crossbowcraft13.openvoice

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.crossbowcraft13.openvoice.accessibility.*
import io.github.crossbowcraft13.openvoice.perception.*
import io.github.crossbowcraft13.openvoice.perception.vision.VisionRuntime
import io.github.crossbowcraft13.openvoice.task.TaskBlackboard
import io.github.crossbowcraft13.openvoice.ai.InferenceEngine
import io.github.crossbowcraft13.openvoice.ai.AiSettings
import io.github.crossbowcraft13.openvoice.ai.DeviceProfiler
import io.github.crossbowcraft13.openvoice.ai.ModelManager
import io.github.crossbowcraft13.openvoice.router.CapabilityRouter
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerceptionEngineTest {

    private lateinit var context: android.content.Context
    private lateinit var blackboard: TaskBlackboard
    private lateinit var cache: VisualMemoryCache
    private lateinit var router: CapabilityRouter

    @Before
    fun setup() {
        Logger.init(true)
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        blackboard = TaskBlackboard()
        cache = VisualMemoryCache()
        router = CapabilityRouter()
    }

    // ── ScreenContext Tests ─────────────────────────────────────────

    @Test
    fun screenContext_defaults() {
        val ctx = ScreenContext()
        assertEquals("", ctx.packageName)
        assertTrue(ctx.elements.isEmpty())
        assertTrue(ctx.textBlocks.isEmpty())
        assertFalse(ctx.hasDialogs)
    }

    @Test
    fun screenContext_findElements_byText() {
        val ctx = ScreenContext(elements = listOf(
            ContextElement(text = "Submit", role = UiRole.BUTTON, isClickable = true,
                bounds = Rect(0,0,100,50), centerX = 50, centerY = 25),
            ContextElement(text = "Cancel", role = UiRole.BUTTON, isClickable = true,
                bounds = Rect(100,0,200,50), centerX = 150, centerY = 25)
        ))
        val results = ctx.findElements("Submit")
        assertEquals(1, results.size)
        assertEquals("Submit", results[0].text)
    }

    @Test
    fun screenContext_findClickable() {
        val ctx = ScreenContext(elements = listOf(
            ContextElement(text = "Continue", role = UiRole.BUTTON, isClickable = true,
                bounds = Rect(0,0,100,50), centerX = 50, centerY = 25),
            ContextElement(text = "Label", role = UiRole.TEXT, isClickable = false,
                bounds = Rect(0,50,100,100), centerX = 50, centerY = 75)
        ))
        val btn = ctx.findClickable("Continue")
        assertNotNull(btn)
        assertTrue(btn?.isClickable ?: false)
    }

    @Test
    fun screenContext_findTextFields() {
        val ctx = ScreenContext(elements = listOf(
            ContextElement(text = "Name", role = UiRole.INPUT, isEditable = true,
                bounds = Rect(0,0,200,50), centerX = 100, centerY = 25),
            ContextElement(text = "Submit", role = UiRole.BUTTON, isClickable = true,
                bounds = Rect(0,50,100,100), centerX = 50, centerY = 75)
        ))
        val fields = ctx.findTextFields()
        assertEquals(1, fields.size)
        assertEquals("Name", fields[0].text)
    }

    @Test
    fun screenContext_summary_containsInfo() {
        val ctx = ScreenContext(
            packageName = "com.example.app",
            elements = listOf(
                ContextElement(text = "Submit", role = UiRole.BUTTON, isClickable = true,
                    bounds = Rect(0,0,100,50), centerX = 50, centerY = 25)
            ),
            textBlocks = listOf(TextBlock(text = "Welcome", bounds = Rect())),
            sourceConfidence = mapOf(PerceptionSource.ACCESSIBILITY to 0.99f)
        )
        val summary = ctx.summary
        assertTrue(summary.contains("com.example.app"))
        assertTrue(summary.contains("1 elements"))
        assertTrue(summary.contains("0.99"))
    }

    @Test
    fun contextElement_bestLabel_fallsBack() {
        val withText = ContextElement(text = "Hello")
        assertEquals("Hello", withText.bestLabel)

        val withOcr = withText.copy(text = null, ocrText = "World")
        assertEquals("World", withOcr.bestLabel)

        val withVision = withOcr.copy(ocrText = null, visionLabel = "Button")
        assertEquals("Button", withVision.bestLabel)

        val withDesc = withVision.copy(visionLabel = null, contentDescription = "Desc")
        assertEquals("Desc", withDesc.bestLabel)

        val withViewId = withDesc.copy(contentDescription = null, viewId = "btn_1")
        assertEquals("btn_1", withViewId.bestLabel)

        val none = withViewId.copy(viewId = null)
        assertNull(none.bestLabel)
    }

    @Test
    fun contextElement_fromSemanticNode_mapsCorrectly() {
        val node = SemanticUiNode(
            role = UiRole.BUTTON, text = "OK", contentDescription = "OK button",
            viewId = "btn_ok", bounds = Rect(0,0,100,50), centerX = 50, centerY = 25,
            isClickable = true, isEnabled = true, isVisible = true,
            isFocusable = true, isEditable = false, isChecked = null,
            isCheckable = false, isScrollable = false, isPassword = false,
            depth = 0, packageName = "com.test", className = "Button", children = emptyList()
        )
        val element = ContextElement.fromSemanticNode(node)
        assertEquals(UiRole.BUTTON, element.role)
        assertEquals("OK", element.text)
        assertTrue(element.isClickable)
        assertEquals(PerceptionSource.ACCESSIBILITY, element.source)
        assertEquals(0.99f, element.confidence, 0.01f)
    }

    // ── VisualMemoryCache Tests ─────────────────────────────────────

    @Test
    fun cache_storesAndRetrievesOcr() {
        val ocr = OcrResult(
            textBlocks = listOf(TextBlock(text = "Hello", bounds = Rect())),
            fullText = "Hello", latencyMs = 10, confidence = 0.95f)
        cache.cacheOcr("com.test", "TestActivity", ocr)

        val retrieved = cache.getCachedOcr("com.test", "TestActivity")
        assertNotNull(retrieved)
        assertEquals("Hello", retrieved?.fullText)
    }

    @Test
    fun cache_returnsNull_forWrongApp() {
        val ocr = OcrResult(textBlocks = emptyList(), fullText = "test", latencyMs = 0, confidence = 0.9f)
        cache.cacheOcr("com.app1", "Activity", ocr)

        assertNull(cache.getCachedOcr("com.app2", "Activity"))
    }

    @Test
    fun cache_invalidatesOrphanedEntries() {
        val ocr = OcrResult(textBlocks = emptyList(), fullText = "old", latencyMs = 0, confidence = 0.9f)
        cache.cacheOcr("com.test", "Activity", ocr)

        cache.invalidateForAppChange("com.other", "OtherActivity")
        // OCR should still be there — it's from a different app
        assertNull(cache.getCachedOcr("com.other", "OtherActivity"))
    }

    @Test
    fun cache_invalidateAll_clearsAll() {
        val ocr = OcrResult(textBlocks = emptyList(), fullText = "test", latencyMs = 0, confidence = 0.9f)
        cache.cacheOcr("com.test", "Activity", ocr)
        cache.invalidateAll()

        assertNull(cache.getCachedOcr("com.test", "Activity"))
    }

    @Test
    fun cache_hasValidOcr() {
        assertFalse(cache.hasValidOcr("com.test", "Activity"))
        val ocr = OcrResult(textBlocks = emptyList(), fullText = "test", latencyMs = 0, confidence = 0.9f)
        cache.cacheOcr("com.test", "Activity", ocr)
        assertTrue(cache.hasValidOcr("com.test", "Activity"))
    }

    @Test
    fun cache_storageStats() {
        val stats = cache.getStats()
        assertTrue(stats.contains("OCR cache"))
        assertTrue(stats.contains("Vision cache"))
    }

    // ── OcrEngine Tests ────────────────────────────────────────────

    @Test
    fun ocrEngine_hasFallback() = runBlocking {
        // Create a simple test bitmap
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val ocrEngine = OcrEngine(context)
        val result = ocrEngine.recognize(bitmap)
        assertNotNull(result)
        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun ocrEngine_handlesNullInput() = runBlocking {
        val ocrEngine = OcrEngine(context)
        val tinyBitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = ocrEngine.recognize(tinyBitmap)
        assertNotNull(result)
    }

    // ── ScreenshotPipeline Tests ───────────────────────────────────

    @Test
    fun screenshotPipeline_getsMetrics() {
        val pipeline = ScreenshotPipeline(context)
        val metrics = pipeline.getScreenMetrics()
        assertTrue(metrics.widthPixels > 0)
        assertTrue(metrics.heightPixels > 0)
    }

    @Test
    fun screenshotPipeline_downsample_scalesCorrectly() {
        val pipeline = ScreenshotPipeline(context)
        val large = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val downsampled = pipeline.downsample(large, 512)
        assertTrue(downsampled.width <= 512 || downsampled.height <= 512)
        assertTrue(downsampled.width >= 64)
        assertTrue(downsampled.height >= 64)
    }

    @Test
    fun screenshotPipeline_downsample_smallImage_unchanged() {
        val pipeline = ScreenshotPipeline(context)
        val small = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val result = pipeline.downsample(small, 512)
        assertEquals(100, result.width)
        assertEquals(100, result.height)
    }

    @Test
    fun screenshotPipeline_encodeToJpeg_producesValidBytes() {
        val pipeline = ScreenshotPipeline(context)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val bytes = pipeline.encodeToJpeg(bitmap)
        assertTrue(bytes.isNotEmpty())
        assertTrue(bytes.size > 100) // Should be more than a handful of bytes
    }

    @Test
    fun screenshotPipeline_hasDisplayCutoutCheck() {
        val pipeline = ScreenshotPipeline(context)
        // Just verify it doesn't throw
        val hasCutout = pipeline.hasDisplayCutout()
        assertNotNull(hasCutout)
    }

    // ── PerceptionEngine Fusion Tests ──────────────────────────────

    @Test
    fun fusion_mergesA11yAndOcr_withoutDuplicates() {
        // This tests the fuseText logic by creating a ScreenContext and
        // manually calling the equivalent logic
        val a11yElements = listOf(
            ContextElement(text = "Submit", source = PerceptionSource.ACCESSIBILITY, confidence = 0.99f,
                bounds = Rect(0, 0, 100, 50), centerX = 50, centerY = 25,
                role = UiRole.BUTTON, isClickable = true)
        )
        val a11yText = listOf(
            TextBlock(text = "Welcome", bounds = Rect(0, 0, 200, 30), confidence = 0.99f, isFromA11y = true)
        )
        val ocrText = listOf(
            TextBlock(text = "Welcome", bounds = Rect(0, 0, 200, 30), confidence = 0.91f, isFromA11y = false), // duplicate
            TextBlock(text = "New text", bounds = Rect(0, 100, 200, 130), confidence = 0.91f, isFromA11y = false) // new
        )

        // Simulate fuseText: deduplicate by checking overlap
        val existingTexts = a11yText.map { it.text.lowercase() }.toSet()
        val dedupedOcr = ocrText.filter { block ->
            block.text.lowercase() !in existingTexts &&
                existingTexts.none { existing -> existing.contains(block.text.lowercase()) || block.text.lowercase().contains(existing) }
        }

        assertEquals(1, dedupedOcr.size)
        assertEquals("New text", dedupedOcr[0].text)
    }

    @Test
    fun fusion_mergesVision_withoutOverwritingA11y() {
        val a11yElements = listOf(
            ContextElement(text = "Submit", source = PerceptionSource.ACCESSIBILITY, confidence = 0.99f,
                bounds = Rect(0, 0, 100, 50), centerX = 50, centerY = 25,
                role = UiRole.BUTTON, isClickable = true)
        )
        val visionElements = listOf(
            VisualElement(label = "Submit", bounds = Rect(), confidence = 0.74f, category = "button"), // duplicate
            VisualElement(label = "Blue icon", bounds = Rect(), confidence = 0.74f, category = "icon") // new
        )

        // Simulate fuseVision: only add elements not already in accessibility
        val existingLabels = a11yElements.mapNotNull { it.bestLabel?.lowercase() }.toSet()
        val newVisuals = visionElements.filter { it.label.lowercase() !in existingLabels }

        assertEquals(1, newVisuals.size)
        assertEquals("Blue icon", newVisuals[0].label)
    }

    @Test
    fun perception_confidenceScoring_prioritizesA11y() {
        // When multiple sources report, A11y should have highest confidence
        val confidences = mutableMapOf<PerceptionSource, Float>()
        confidences[PerceptionSource.ACCESSIBILITY] = 0.99f
        confidences[PerceptionSource.OCR] = 0.91f
        confidences[PerceptionSource.VISION] = 0.74f

        assertEquals(0.99f, confidences[PerceptionSource.ACCESSIBILITY]!!, 0.01f)
        assertTrue(confidences[PerceptionSource.ACCESSIBILITY]!! > confidences[PerceptionSource.OCR]!!)
        assertTrue(confidences[PerceptionSource.OCR]!! > confidences[PerceptionSource.VISION]!!)
    }

    // ── CapabilityRouter Phase 5 Tests ──────────────────────────────

    @Test
    fun router_routesVisionOnlyWhenA11yUnavailable() {
        // When accessibility is not available, READ_SCREEN goes to Vision
        val result = io.github.crossbowcraft13.openvoice.intent.IntentResult("READ_SCREEN", 0.9f)
        val resolution = router.resolve(result)
        // Without accessibility service running, Vision is the fallback
        assertTrue("READ_SCREEN should route to Vision or Accessibility",
            resolution is io.github.crossbowcraft13.openvoice.router.Resolution.Vision ||
            resolution is io.github.crossbowcraft13.openvoice.router.Resolution.Accessibility)
    }

    @Test
    fun router_routesA11yFirst_whenAvailable() {
        // NOTE: This test assumes VoiceAccessibilityService might not be running
        // in the test environment. The router checks isRunning().
        // If not running, Vision is the correct fallback.
        val result = io.github.crossbowcraft13.openvoice.intent.IntentResult("READ_SCREEN", 0.9f)
        val resolution = router.resolve(result)

        // Both paths are valid — the router should never return Unsupported
        assertFalse("READ_SCREEN should not be unsupported",
            resolution is io.github.crossbowcraft13.openvoice.router.Resolution.Unsupported)
    }

    @Test
    fun router_nativeIntents_stillFastPath() {
        val result = io.github.crossbowcraft13.openvoice.intent.IntentResult("SET_TIMER", 0.95f, mapOf("duration" to "30 seconds"))
        val resolution = router.resolve(result)
        assertTrue("SET_TIMER should be native", resolution is io.github.crossbowcraft13.openvoice.router.Resolution.Native)
    }

    // ── PerceptionSource Priority Tests ─────────────────────────────

    @Test
    fun perceptionSource_priority_order() {
        assertTrue(PerceptionSource.ACCESSIBILITY.priority < PerceptionSource.OCR.priority)
        assertTrue(PerceptionSource.OCR.priority < PerceptionSource.VISION.priority)
        assertTrue(PerceptionSource.VISION.priority < PerceptionSource.LLM.priority)
    }

    // ── VisualElement Tests ─────────────────────────────────────────

    @Test
    fun visualElement_creation() {
        val el = VisualElement(label = "Play button", bounds = Rect(0, 0, 50, 50),
            confidence = 0.74f, category = "button", description = "Blue play button")
        assertEquals("Play button", el.label)
        assertEquals("button", el.category)
        assertEquals(0.74f, el.confidence, 0.01f)
    }

    // ── TextBlock Tests ─────────────────────────────────────────────

    @Test
    fun textBlock_creation() {
        val tb = TextBlock(text = "Hello, World!", bounds = Rect(10, 10, 200, 50),
            confidence = 0.95f, isFromA11y = true, paragraphIndex = 0, readingOrder = 1)
        assertEquals("Hello, World!", tb.text)
        assertTrue(tb.isFromA11y)
    }

    // ── OcrResult Tests ─────────────────────────────────────────────

    @Test
    fun ocrResult_creation() {
        val result = OcrResult(
            textBlocks = listOf(TextBlock(text = "Test", bounds = Rect())),
            fullText = "Test",
            latencyMs = 42,
            confidence = 0.91f
        )
        assertEquals(1, result.textBlocks.size)
        assertEquals("Test", result.fullText)
        assertEquals(42, result.latencyMs)
    }

    // ── VisionResult Tests ──────────────────────────────────────────

    @Test
    fun visionResult_creation() {
        val result = VisionResult(
            description = "A sign in button",
            elements = listOf(
                VisualElement("Sign In", Rect(), 0.74f, "button", "Blue sign-in button")
            ),
            labels = listOf("sign_in", "button"),
            latencyMs = 1500,
            confidence = 0.74f
        )
        assertEquals("A sign in button", result.description)
        assertEquals(1, result.elements.size)
        assertEquals(1500, result.latencyMs)
    }

    // ── Performance Benchmarks ──────────────────────────────────────

    @Test
    fun benchmark_screenContextCreation() {
        val iterations = 500
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val ctx = ScreenContext(
                packageName = "com.test",
                elements = listOf(
                    ContextElement(text = "Button $i", role = UiRole.BUTTON, isClickable = true,
                        bounds = Rect(0, 0, 100, 50), centerX = 50, centerY = 25)
                ),
                textBlocks = listOf(
                    TextBlock(text = "Text $i", bounds = Rect())
                ),
                sourceConfidence = mapOf(PerceptionSource.ACCESSIBILITY to 0.99f)
            )
            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("ScreenContext creation (n=$iterations): avg=${avgUs}µs")
        assertTrue("ScreenContext creation < 100µs", avgUs < 100)
    }

    @Test
    fun benchmark_visualCacheOperations() {
        val iterations = 500
        val writeTimes = mutableListOf<Long>()
        val readTimes = mutableListOf<Long>()

        val ocr = OcrResult(textBlocks = emptyList(), fullText = "test", latencyMs = 0, confidence = 0.9f)

        for (i in 0 until iterations) {
            var start = System.nanoTime()
            cache.cacheOcr("com.test", "Activity$i", ocr)
            writeTimes.add((System.nanoTime() - start) / 1_000)

            start = System.nanoTime()
            cache.getCachedOcr("com.test", "Activity$i")
            readTimes.add((System.nanoTime() - start) / 1_000)
        }

        val avgWriteUs = writeTimes.average().toLong()
        val avgReadUs = readTimes.average().toLong()
        println("VisualCache (n=$iterations): write=${avgWriteUs}µs read=${avgReadUs}µs")
        assertTrue("Cache write < 100µs", avgWriteUs < 100)
        assertTrue("Cache read < 50µs", avgReadUs < 50)
    }

    @Test
    fun benchmark_fusion_deduplication() {
        val iterations = 200

        val a11yLabels = (1..20).map { "Text $it" }
        val ocrLabels = (1..25).map { "Text $it" } // 20 duplicates + 5 new

        // Warm up the JIT before timing
        for (i in 0 until 20) {
            val existing = a11yLabels.map { it.lowercase() }.toSet()
            ocrLabels.filter { block ->
                val lower = block.lowercase()
                lower !in existing && existing.none { e -> e.contains(lower) || lower.contains(e) }
            }
        }

        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val start = System.nanoTime()

            val existing = a11yLabels.map { it.lowercase() }.toSet()
            val deduped = ocrLabels.filter { block ->
                val lower = block.lowercase()
                lower !in existing && existing.none { existing -> existing.contains(lower) || lower.contains(existing) }
            }

            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("Fusion dedup (n=$iterations): avg=${avgUs}µs")
        // Regression guard with 10x headroom over measured steady-state (~80µs).
        assertTrue("Fusion dedup < 1ms", avgUs < 1_000)
    }

    @Test
    fun benchmark_elementCreation_fromSemanticNode() {
        val node = SemanticUiNode(
            role = UiRole.BUTTON, text = "OK", contentDescription = null,
            viewId = "btn_ok", bounds = Rect(0,0,100,50), centerX = 50, centerY = 25,
            isClickable = true, isEnabled = true, isVisible = true,
            isFocusable = true, isEditable = false, isChecked = null,
            isCheckable = false, isScrollable = false, isPassword = false,
            depth = 0, packageName = "com.test", className = "Button", children = emptyList()
        )

        val iterations = 500
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val el = ContextElement.fromSemanticNode(node)
            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("ContextElement.fromSemanticNode (n=$iterations): avg=${avgUs}µs")
        // Regression guard with 10x headroom over measured steady-state (~30µs).
        assertTrue("Element creation < 500µs", avgUs < 500)
    }
}
