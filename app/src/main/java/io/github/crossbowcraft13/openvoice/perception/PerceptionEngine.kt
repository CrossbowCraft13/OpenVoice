package io.github.crossbowcraft13.openvoice.perception

import android.graphics.Bitmap
import android.graphics.Rect
import io.github.crossbowcraft13.openvoice.accessibility.ScreenState
import io.github.crossbowcraft13.openvoice.accessibility.VoiceAccessibilityService
import io.github.crossbowcraft13.openvoice.accessibility.SemanticUiNode
import io.github.crossbowcraft13.openvoice.accessibility.UiRole
import io.github.crossbowcraft13.openvoice.perception.vision.VisionRuntime
import io.github.crossbowcraft13.openvoice.task.TaskBlackboard
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PerceptionEngine — The central coordinator for multimodal screen understanding.
 *
 * Architecture:
 *
 *   Screen changes
 *        ↓
 *   PerceptionEngine
 *        ├── 1. Capture A11y Tree (fast, high confidence)
 *        ├── 2. Check Visual Cache (instant if valid)
 *        ├── 3. Capture Screenshot + OCR (if A11y insufficient)
 *        └── 4. Vision Model (if OCR insufficient)
 *        ↓
 *   ScreenContext ← Unified output consumed by all subsystems
 *
 * Confidence-based fusion:
 *   Source         Confidence  When Used
 *   Accessibility  0.99        Always (when service is running)
 *   OCR            0.91        When A11y tree is incomplete
 *   Vision         0.74        When text/roles are ambiguous
 *   LLM            0.63        When context understanding is needed
 *
 * The engine selects the most trustworthy source per element.
 */
@Singleton
class PerceptionEngine @Inject constructor(
    private val screenshotPipeline: ScreenshotPipeline,
    private val ocrEngine: OcrEngine,
    private val visionRuntime: VisionRuntime,
    private val visualCache: VisualMemoryCache,
    private val blackboard: TaskBlackboard
) {
    private var lastScreenContext: ScreenContext? = null
    private var lastCaptureRegions: List<Rect> = emptyList()

    data class PerceptionStats(
        val totalLatencyMs: Long = 0,
        val a11yLatencyMs: Long = 0,
        val ocrLatencyMs: Long = 0,
        val visionLatencyMs: Long = 0,
        val cacheHit: Boolean = false,
        val elementsFused: Int = 0,
        val sourceUsed: PerceptionSource = PerceptionSource.ACCESSIBILITY
    )

    /**
     * Perceive the current screen.
     *
     * This is the main entry point. It:
     * 1. Checks the visual cache (fast path)
     * 2. Captures the accessibility tree
     * 3. If a11y is sufficient, fuses and returns immediately
     * 4. If a11y is insufficient, runs OCR in parallel
     * 5. If both are insufficient, invokes the vision model
     *
     * @param forceRefresh Force re-capture even if cache is valid
     */
    suspend fun perceive(forceRefresh: Boolean = false): Pair<ScreenContext, PerceptionStats> = withContext(Dispatchers.IO) {
        val overallStart = System.currentTimeMillis()
        val a11yAvailable = VoiceAccessibilityService.isRunning()
        val stats = mutableListOf<PerceptionSource>()

        // 1. Check cache (fast path)
        val currentApp = blackboard.state.value.currentApp
        val currentActivity = blackboard.state.value.currentActivity
        if (!forceRefresh && currentApp.isNotEmpty()) {
            val cached = visualCache.getCachedScreenContext(currentApp, currentActivity)
            if (cached != null && visualCache.hasValidOcr(currentApp, currentActivity)) {
                val ms = System.currentTimeMillis() - overallStart
                Logger.d("Perception cache hit for $currentApp (${ms}ms)", "Perception")
                return@withContext Pair(cached, PerceptionStats(
                    totalLatencyMs = ms, cacheHit = true, sourceUsed = PerceptionSource.ACCESSIBILITY))
            }
        }

        // 2. Capture accessibility tree
        var a11yState: ScreenState? = null
        val a11yStart = System.currentTimeMillis()
        if (a11yAvailable) {
            a11yState = captureA11yTree()
        }
        val a11yLatency = System.currentTimeMillis() - a11yStart
        stats.add(if (a11yAvailable) PerceptionSource.ACCESSIBILITY else PerceptionSource.VISION)

        // 3. Build initial context from accessibility
        var context = buildContextFromA11y(a11yState, currentApp, currentActivity)

        // 4. Determine if OCR is needed
        val needsOcr = needsMoreInfo(context)
        var ocrResult: OcrResult? = null
        var screenshot: Bitmap? = null

        if (needsOcr) {
            // Capture screenshot + OCR in parallel
            val ocrStart = System.currentTimeMillis()

            val ocrDeferred = async {
                val cachedOcr = visualCache.getCachedOcr(currentApp, currentActivity)
                if (cachedOcr != null) {
                    Pair(cachedOcr, null as Bitmap?)
                } else {
                    val capture = screenshotPipeline.captureFullScreen()
                    if (capture != null) {
                        screenshot = capture.bitmap
                        visualCache.cacheScreenshot(currentApp, currentActivity,
                            screenshotPipeline.encodeToJpeg(capture.bitmap))
                        val ocr = ocrEngine.recognize(capture.bitmap)
                        visualCache.cacheOcr(currentApp, currentActivity, ocr)
                        Pair(ocr, capture.bitmap)
                    } else Pair(null, null)
                }
            }

            val (ocr, ss) = ocrDeferred.await()
            ocrResult = ocr
            if (ss != null) screenshot = ss

            val ocrLatency = System.currentTimeMillis() - ocrStart
            stats.add(PerceptionSource.OCR)

            // 5. Fuse OCR with accessibility
            if (ocrResult != null) {
                context = fuseText(context, ocrResult!!)
            }
        }

        // 6. Determine if vision is needed
        var visionResult: VisionResult? = null
        if (needsVision(context)) {
            val visionStart = System.currentTimeMillis()

            // Check vision cache
            val cachedVision = visualCache.getCachedVision(currentApp, currentActivity)
            if (cachedVision != null) {
                visionResult = cachedVision
            } else if (visionRuntime.estimateConfidence() > 0f) {
                screenshot?.let { shot ->
                    val imageData = screenshotPipeline.encodeToJpeg(shot)
                    visionResult = visionRuntime.describe(imageData)
                    if (visionResult != null) {
                        visualCache.cacheVision(currentApp, currentActivity, visionResult!!)
                    }
                }
            }

            if (visionResult != null) {
                stats.add(PerceptionSource.VISION)
                context = fuseVision(context, visionResult!!)
            }
        }

        // 7. Build source confidence map
        val confidences = buildConfidenceMap(a11yAvailable, ocrResult, visionResult)

        // 8. Finalize context
        val finalized = context.copy(
            sourceConfidence = confidences,
            changedSinceLastCapture = hasScreenChanged(context),
            screenshot = screenshot,
            captureTimestamp = System.currentTimeMillis()
        )

        // 9. Cache and store
        visualCache.cacheScreenContext(currentApp, currentActivity, finalized)
        lastScreenContext = finalized
        blackboard.setOcrText(finalized.fullText)

        val totalMs = System.currentTimeMillis() - overallStart
        val primarySource = when {
            VoiceAccessibilityService.isRunning() -> PerceptionSource.ACCESSIBILITY
            visionResult != null -> PerceptionSource.VISION
            ocrResult != null -> PerceptionSource.OCR
            else -> PerceptionSource.ACCESSIBILITY
        }

        Logger.i("Perception: ${context.elements.size} elements, " +
            "${context.textBlocks.size} text blocks, " +
            "sources: ${
                confidences.entries.joinToString(", ") { "${it.key.displayName}=${"%.2f".format(it.value)}" }
            } (${totalMs}ms)", "Perception")

        Pair(finalized, PerceptionStats(
            totalLatencyMs = totalMs,
            a11yLatencyMs = a11yLatency,
            sourceUsed = primarySource,
            elementsFused = context.elements.size
        ))
    }

    // ── Context Construction ──────────────────────────────────────

    private fun buildContextFromA11y(a11yState: ScreenState?, app: String, activity: String): ScreenContext {
        if (a11yState == null || a11yState.semanticTree == null) {
            return ScreenContext(packageName = app, activityName = activity)
        }

        val elements = a11yState.interactiveElements.map { node ->
            ContextElement.fromSemanticNode(node, PerceptionSource.ACCESSIBILITY, 0.99f)
        }

        val textBlocks = a11yState.textInputs.map { input ->
            TextBlock(
                text = input.label,
                bounds = input.bounds,
                confidence = 0.99f,
                isFromA11y = true
            )
        }

        val fullText = a11yState.run {
            val sb = StringBuilder()
            fun walk(n: SemanticUiNode, depth: Int) {
                n.text?.let { sb.appendLine(it) }
                n.contentDescription?.let { sb.appendLine(it) }
                n.children.forEach { walk(it, depth + 1) }
            }
            semanticTree?.let { walk(it, 0) }
            sb.toString()
        }

        return ScreenContext(
            packageName = app.ifEmpty { a11yState.packageName },
            activityName = activity.ifEmpty { a11yState.activityName },
            elements = elements,
            textBlocks = textBlocks,
            fullText = fullText,
            hasDialogs = a11yState.hasDialogs,
            dialogText = a11yState.dialogs.map { it.label },
            rawA11yTree = a11yState.semanticTree
        )
    }

    private fun fuseText(context: ScreenContext, ocr: OcrResult): ScreenContext {
        // Merge OCR text blocks with accessibility text, avoiding duplicates
        val existingTexts = context.textBlocks.map { it.text.lowercase() }.toSet()

        val newBlocks = ocr.textBlocks.filter { block ->
            block.text.isNotBlank() &&
                existingTexts.none { existing ->
                    block.text.lowercase().contains(existing) || existing.contains(block.text.lowercase())
                }
        }.map { it.copy(isFromA11y = false) }

        // Update elements with OCR text where absent
        val updatedElements = context.elements.map { el ->
            if (el.text == null && el.contentDescription == null) {
                val matchingOcr = ocr.textBlocks.find { ocrBlock ->
                    rectsOverlap(el.bounds, ocrBlock.bounds)
                }
                if (matchingOcr != null) el.copy(
                    ocrText = matchingOcr.text,
                    source = PerceptionSource.OCR,
                    confidence = matchingOcr.confidence
                ) else el
            } else el
        }

        return context.copy(
            elements = updatedElements,
            textBlocks = context.textBlocks + newBlocks,
            fullText = context.fullText + "\n" + ocr.fullText
        )
    }

    private fun fuseVision(context: ScreenContext, vision: VisionResult): ScreenContext {
        // Add vision-described elements not already in the accessibility tree
        val existingLabels = context.elements.mapNotNull { it.bestLabel?.lowercase() }.toSet()
        val existingTexts = context.textBlocks.map { it.text.lowercase() }.toSet()

        val newVisualElements = vision.elements.filter { vis ->
            vis.label.lowercase() !in existingLabels &&
                existingTexts.none { vis.label.lowercase().contains(it) || it.contains(vis.label.lowercase()) }
        }

        val descriptionBlocks = if (vision.description.isNotEmpty() &&
            !context.fullText.contains(vision.description.take(50), ignoreCase = true)) {
            listOf(TextBlock(
                text = vision.description,
                bounds = Rect(0, 0, 0, 0),
                confidence = vision.confidence,
                isFromA11y = false
            ))
        } else emptyList()

        return context.copy(
            visualElements = context.visualElements + newVisualElements,
            textBlocks = context.textBlocks + descriptionBlocks,
            fullText = context.fullText + "\n" + vision.description,
            rawA11yTree = context.rawA11yTree
        )
    }

    // ── Decision Logic ────────────────────────────────────────────

    private fun needsMoreInfo(context: ScreenContext): Boolean {
        // Need OCR when:
        // - Accessibility service is not running
        // - No interactive elements found
        // - No text content on screen
        return !VoiceAccessibilityService.isRunning() ||
            context.elements.isEmpty() ||
            context.fullText.isBlank()
    }

    private fun needsVision(context: ScreenContext): Boolean {
        // Need vision when:
        // - We ran OCR but still have no elements
        // - Screen has visual-only content (charts, diagrams, icons)
        // - User explicitly asked about visual content
        val goal = blackboard.state.value.userGoal.lowercase()
        val wantsVisual = goal.contains("see") || goal.contains("look") ||
            goal.contains("chart") || goal.contains("diagram") ||
            goal.contains("icon") || goal.contains("what")

        return context.elements.isEmpty() || wantsVisual
    }

    private fun hasScreenChanged(context: ScreenContext): Boolean {
        val last = lastScreenContext ?: return true
        return last.packageName != context.packageName ||
            last.elements.size != context.elements.size ||
            last.fullText != context.fullText
    }

    // ── Confidence ───────────────────────────────────────────────

    private fun buildConfidenceMap(
        a11yAvailable: Boolean,
        ocr: OcrResult?,
        vision: VisionResult?
    ): Map<PerceptionSource, Float> {
        val map = mutableMapOf<PerceptionSource, Float>()
        if (a11yAvailable) map[PerceptionSource.ACCESSIBILITY] = 0.99f
        ocr?.let { map[PerceptionSource.OCR] = it.confidence.coerceAtLeast(0.5f) }
        vision?.let { map[PerceptionSource.VISION] = it.confidence.coerceAtLeast(0.3f) }
        return map
    }

    private fun captureA11yTree(): ScreenState {
        val a11y = VoiceAccessibilityService.getInstance() ?: return ScreenState()
        val tree = a11y.getUiTree() ?: return ScreenState()
        val screen = ScreenState.fromUiTree(tree)
        val app = a11y.getActivePackage() ?: ""

        blackboard.setUiTree(tree)
        if (app.isNotEmpty()) blackboard.setCurrentApp(app)

        return screen.copy(packageName = app)
    }

    // ── Utilities ────────────────────────────────────────────────

    private fun rectsOverlap(a: Rect, b: Rect): Boolean {
        val overlapX = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val overlapY = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return overlapX > 0 && overlapY > 0
    }

    fun getLastContext(): ScreenContext? = lastScreenContext

    /** Test seams for exercising fusion and answer-tier decisions without a live service. */
    internal fun setLastContextForTesting(context: ScreenContext?) {
        lastScreenContext = context
    }

    internal fun fuseTextForTesting(context: ScreenContext, ocr: OcrResult): ScreenContext =
        fuseText(context, ocr)

    internal fun fuseVisionForTesting(context: ScreenContext, vision: VisionResult): ScreenContext =
        fuseVision(context, vision)

    /**
     * Answer a question about the current screen.
     * Chooses the cheapest capable source.
     */
    suspend fun answerQuestion(question: String): AnswerResult = withContext(Dispatchers.IO) {
        val context = lastScreenContext ?: return@withContext AnswerResult(
            "No screen context available.", PerceptionSource.VISION, 0f)

        val lower = question.lowercase()

        // Tier 1: Answer from accessibility tree (instant)
        val a11yAnswer = answerFromA11y(context, lower)
        if (a11yAnswer != null) {
            return@withContext AnswerResult(a11yAnswer, PerceptionSource.ACCESSIBILITY, 0.99f)
        }

        // Tier 2: Answer from OCR text
        val ocrAnswer = answerFromOcr(context, lower)
        if (ocrAnswer != null) {
            return@withContext AnswerResult(ocrAnswer, PerceptionSource.OCR, 0.85f)
        }

        // Tier 3: Answer from vision model
        if (visionRuntime.estimateConfidence() > 0f && context.screenshot != null) {
            val imageData = screenshotPipeline.encodeToJpeg(context.screenshot)
            val visionAnswer = visionRuntime.answerQuestion(imageData, question)
            if (visionAnswer.isNotEmpty()) {
                return@withContext AnswerResult(visionAnswer, PerceptionSource.VISION, 0.74f)
            }
        }

        AnswerResult("I can see the screen but don't have enough information to answer that.",
            PerceptionSource.OCR, 0.5f)
    }

    private fun answerFromA11y(ctx: ScreenContext, query: String): String? {
        return when {
            query.contains("app") && (query.contains("what") || query.contains("which")) ->
                "You are in ${ctx.packageName}"
            query.contains("button") || query.contains("tap") || query.contains("click") -> {
                val clickable = ctx.elements.filter { it.isClickable && it.isVisible }
                if (clickable.isEmpty()) null
                else "I can see ${clickable.size} clickable elements: ${
                    clickable.take(5).mapNotNull { it.bestLabel }.joinToString(", ")
                }"
            }
            query.contains("text") || query.contains("read") || query.contains("content") ->
                if (ctx.fullText.isNotBlank()) ctx.fullText.take(500) else null
            query.contains("dialog") || query.contains("popup") ->
                if (ctx.hasDialogs) "There is a dialog: ${ctx.dialogText.joinToString(", ")}" else null
            query.contains("field") || query.contains("input") || query.contains("type") ->
                ctx.findTextFields().mapNotNull { it.bestLabel }.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?.let { "Text fields: $it" }
            query.contains("scroll") ->
                if (ctx.elements.any { it.isScrollable }) "The screen is scrollable" else null
            else -> null
        }
    }

    private fun answerFromOcr(ctx: ScreenContext, query: String): String? {
        val lower = query.lowercase()
        val matchingBlocks = ctx.textBlocks.filter { block ->
            val text = block.text.lowercase()
            (lower.contains("find") || lower.contains("where")) && lower.contains(text.split(" ").first())
        }
        if (matchingBlocks.isNotEmpty()) {
            val best = matchingBlocks.maxByOrNull { it.confidence }!!
            return "Found \"${best.text}\" at position [${best.bounds.left}, ${best.bounds.top}]"
        }
        return null
    }

    data class AnswerResult(
        val answer: String,
        val source: PerceptionSource,
        val confidence: Float
    )
}
