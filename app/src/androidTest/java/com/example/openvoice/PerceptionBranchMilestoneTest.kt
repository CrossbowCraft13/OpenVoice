package com.example.openvoice

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.ai.AiSettings
import com.example.openvoice.ai.DeviceProfiler
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.perception.ContextElement
import com.example.openvoice.perception.OcrEngine
import com.example.openvoice.perception.OcrResult
import com.example.openvoice.perception.PerceptionEngine
import com.example.openvoice.perception.PerceptionSource
import com.example.openvoice.perception.ScreenContext
import com.example.openvoice.perception.ScreenshotPipeline
import com.example.openvoice.perception.TextBlock
import com.example.openvoice.perception.VisualElement
import com.example.openvoice.perception.VisualMemoryCache
import com.example.openvoice.perception.VisionResult
import com.example.openvoice.perception.vision.VisionRuntime
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerceptionBranchMilestoneTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun engine(): PerceptionEngine {
        val profiler = DeviceProfiler(context)
        val inference = InferenceEngine(context, AiSettings(context), profiler)
        return PerceptionEngine(
            ScreenshotPipeline(context),
            OcrEngine(context),
            VisionRuntime(inference),
            VisualMemoryCache(),
            TaskBlackboard()
        )
    }

    private fun contextWith(
        packageName: String = "com.example.app",
        fullText: String = "",
        elements: List<ContextElement> = emptyList(),
        textBlocks: List<TextBlock> = emptyList(),
        hasDialogs: Boolean = false,
        dialogText: List<String> = emptyList()
    ) = ScreenContext(
        packageName = packageName,
        fullText = fullText,
        elements = elements,
        textBlocks = textBlocks,
        hasDialogs = hasDialogs,
        dialogText = dialogText
    )

    @Test
    fun answerFromAccessibility_coversEveryQuestionTierAndFallback() = runBlocking {
        val clickable = ContextElement(
            text = "Save", bounds = Rect(0, 0, 20, 20), isClickable = true, isVisible = true
        )
        val hidden = ContextElement(
            text = "Hidden", bounds = Rect(), isClickable = true, isVisible = false
        )
        val field = ContextElement(
            contentDescription = "Name", bounds = Rect(0, 30, 20, 50), isEditable = true
        )
        val scrollable = ContextElement(isScrollable = true, isVisible = true)
        val rich = contextWith(
            fullText = "Welcome to the screen",
            elements = listOf(clickable, hidden, field, scrollable),
            textBlocks = listOf(TextBlock("Welcome", Rect(0, 0, 20, 20), confidence = 0.9f)),
            hasDialogs = true,
            dialogText = listOf("Confirm")
        )
        val engine = engine()
        engine.setLastContextForTesting(rich)

        assertTrue(engine.answerQuestion("what app am I in").answer.contains("com.example.app"))
        assertTrue(engine.answerQuestion("which app is open").answer.contains("com.example.app"))
        assertTrue(engine.answerQuestion("which button can I click").answer.contains("Save"))
        assertTrue(engine.answerQuestion("read the text").answer.contains("Welcome"))
        assertTrue(engine.answerQuestion("what content is shown").answer.contains("Welcome"))
        assertTrue(engine.answerQuestion("is there a dialog").answer.contains("Confirm"))
        assertTrue(engine.answerQuestion("is there a popup").answer.contains("Confirm"))
        assertTrue(engine.answerQuestion("what input field is visible").answer.contains("Name"))
        assertTrue(engine.answerQuestion("what should I type").answer.contains("Name"))
        assertTrue(engine.answerQuestion("can I scroll").answer.contains("scrollable"))

        val noDialog = contextWith(fullText = "plain")
        engine.setLastContextForTesting(noDialog)
        assertTrue(engine.answerQuestion("is there a dialog").answer.contains("don't have enough"))
        assertTrue(engine.answerQuestion("what button can I tap").answer.contains("don't have enough"))
        assertTrue(engine.answerQuestion("what input field is visible").answer.contains("don't have enough"))
        assertTrue(engine.answerQuestion("can I scroll").answer.contains("don't have enough"))
        assertTrue(engine.answerQuestion("something unrelated").answer.contains("don't have enough"))

        val emptyText = contextWith(elements = listOf(clickable))
        engine.setLastContextForTesting(emptyText)
        assertTrue(engine.answerQuestion("read this text").answer.contains("don't have enough"))
        assertNotNull(engine.getLastContext())
    }

    @Test
    fun fuseText_coversDuplicatesBlanksOverlapAndMissingLabels() {
        val emptyLabel = ContextElement(bounds = Rect(0, 0, 10, 10))
        val textLabel = ContextElement(text = "Existing", bounds = Rect(0, 0, 10, 10))
        val base = contextWith(
            fullText = "Hello",
            elements = listOf(emptyLabel, textLabel),
            textBlocks = listOf(TextBlock("Hello", Rect(0, 0, 20, 20)))
        )
        val ocr = OcrResult(
            textBlocks = listOf(
                TextBlock("new OCR", Rect(0, 0, 10, 10), confidence = 0.7f),
                TextBlock("", Rect()),
                TextBlock("hello", Rect(0, 0, 10, 10), confidence = 0.8f),
                TextBlock("far away", Rect(100, 100, 110, 110), confidence = 0.6f)
            ),
            fullText = "Hello new OCR",
            confidence = 0.4f
        )
        val fused = engine().fuseTextForTesting(base, ocr)
        assertTrue(fused.textBlocks.any { it.text == "new OCR" && !it.isFromA11y })
        assertTrue(fused.textBlocks.any { it.text == "far away" })
        assertEquals("new OCR", fused.elements[0].ocrText)
        assertNull(fused.elements[1].ocrText)
        assertTrue(fused.fullText.contains("new OCR"))
    }

    @Test
    fun fuseVision_coversExistingLabelsTextAndDescriptions() {
        val base = contextWith(
            fullText = "Existing description",
            elements = listOf(ContextElement(text = "Save")),
            textBlocks = listOf(TextBlock("Known text", Rect()))
        )
        val vision = VisionResult(
            description = "A new visual description",
            elements = listOf(
                VisualElement("Save", Rect(), 0.7f),
                VisualElement("Known text", Rect(), 0.7f),
                VisualElement("New icon", Rect(), 0.7f)
            ),
            confidence = 0.74f
        )
        val fused = engine().fuseVisionForTesting(base, vision)
        assertEquals(1, fused.visualElements.size)
        assertEquals("New icon", fused.visualElements.single().label)
        assertTrue(fused.textBlocks.any { it.text == "A new visual description" })

        val duplicateDescription = engine().fuseVisionForTesting(
            base.copy(fullText = "A duplicate visual description"),
            vision.copy(description = "A duplicate visual description")
        )
        assertFalse(duplicateDescription.textBlocks.any { it.text == vision.description })
        assertEquals(
            "A duplicate visual description\nA duplicate visual description",
            duplicateDescription.fullText
        )
    }
}
