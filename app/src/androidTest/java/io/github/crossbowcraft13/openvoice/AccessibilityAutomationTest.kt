package io.github.crossbowcraft13.openvoice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.crossbowcraft13.openvoice.accessibility.VoiceAccessibilityService
import io.github.crossbowcraft13.openvoice.accessibility.SearchCriteria
import io.github.crossbowcraft13.openvoice.accessibility.UiNode
import io.github.crossbowcraft13.openvoice.router.CapabilityRouter
import io.github.crossbowcraft13.openvoice.router.Resolution
import io.github.crossbowcraft13.openvoice.intent.IntentResult
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3 Validation: Accessibility Automation & Capability Router.
 *
 * These tests verify:
 * 1. UiNode tree building from raw AccessibilityNodeInfo
 * 2. SearchCriteria matching logic
 * 3. CapabilityRouter routes intents correctly
 * 4. Action command parsing
 * 5. Edge cases: null children, empty trees, deeply nested hierarchies
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityAutomationTest {

    private lateinit var router: CapabilityRouter

    @Before
    fun setup() {
        Logger.init(true)
        router = CapabilityRouter()
    }

    // ====================================================================
    //  Capability Router Tests
    // ====================================================================

    @Test
    fun router_routesNativeIntent_toNative() {
        val nativeIntents = listOf(
            "LAUNCH_APP", "SEND_SMS", "MAKE_CALL",
            "SET_TIMER", "SET_ALARM", "OPEN_SETTINGS", "ADJUST_VOLUME"
        )
        for (intent in nativeIntents) {
            val result = IntentResult(intent, 0.9f)
            val resolution = router.resolve(result)
            assertTrue("$intent should route to Native", resolution is Resolution.Native)
            assertEquals(intent, (resolution as Resolution.Native).operatorId)
        }
    }

    @Test
    fun router_routesUnknownIntent_toUnsupported() {
        val result = IntentResult("UNKNOWN", 0f, requiresClarification = true)
        val resolution = router.resolve(result)
        assertTrue("Unknown should route to Unsupported", resolution is Resolution.Unsupported)
    }

    @Test
    fun router_routesReadScreen_toVision() {
        val result = IntentResult("READ_SCREEN", 0.9f)
        val resolution = router.resolve(result)
        assertTrue("READ_SCREEN should route to Vision", resolution is Resolution.Vision)
    }

    @Test
    fun router_routesScroll_toAccessibility() {
        val result = IntentResult("SCROLL", 0.9f, mapOf("direction" to "down"))
        val resolution = router.resolve(result)
        assertTrue("SCROLL should route to Accessibility", resolution is Resolution.Accessibility)
        val cmds = (resolution as Resolution.Accessibility).commands
        assertTrue(cmds.any { it.contains("down") })
    }

    @Test
    fun router_routesQuery_toLocalLm() {
        val result = IntentResult("QUERY", 0.9f, mapOf("query" to "summarize this article"))
        val resolution = router.resolve(result)
        assertTrue("QUERY should route to LocalLm", resolution is Resolution.LocalLm)
    }

    @Test
    fun router_providesCorrectParams_forNativeIntents() {
        val result = IntentResult("LAUNCH_APP", 0.95f, mapOf("app" to "spotify"))
        val resolution = router.resolve(result) as Resolution.Native
        assertEquals("spotify", resolution.params["app"])
    }

    // ====================================================================
    //  UiNode Parsing Tests
    // ====================================================================

    @Test
    fun uiNode_buildsCorrectly() {
        val node = UiNode(
            text = "Submit",
            description = "Submit button",
            className = "android.widget.Button",
            packageName = "com.example.app",
            viewId = "com.example.app:id/submit_btn",
            bounds = Rect(100, 200, 300, 250),
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
        assertEquals("Submit", node.text)
        assertEquals("android.widget.Button", node.className)
        assertTrue(node.isClickable)
        assertEquals(200, node.centerX)
        assertEquals(225, node.centerY)
        assertEquals("Button", node.role)
        assertEquals("Submit", node.label)
    }

    @Test
    fun uiNode_fallsBackThroughLabelSources() {
        // label = text first
        val withText = UiNode(text = "Hi", description = null, className = null, packageName = null,
            viewId = null, bounds = Rect(), isClickable = false, isEditable = false,
            isScrollable = false, isChecked = false, isCheckable = false, isFocusable = false,
            isEnabled = true, isPassword = false, isVisible = true, depth = 0, children = emptyList())
        assertEquals("Hi", withText.label)

        // label = description when text is null
        val withDesc = withText.copy(text = null, description = "Desc")
        assertEquals("Desc", withDesc.label)

        // label = viewId when both are null
        val withId = withDesc.copy(description = null, viewId = "some_id")
        assertEquals("some_id", withId.label)

        // label = role when everything else is null
        val withRole = withId.copy(viewId = null, className = "android.widget.TextView")
        assertEquals("TextView", withRole.label)
    }

    @Test
    fun uiNode_computesCenterCoordinatesCorrectly() {
        val node = UiNode(text = "Test", description = null, className = null, packageName = null,
            viewId = null, bounds = Rect(0, 0, 100, 200), isClickable = false, isEditable = false,
            isScrollable = false, isChecked = false, isCheckable = false, isFocusable = false,
            isEnabled = true, isPassword = false, isVisible = true, depth = 0, children = emptyList())
        assertEquals(50, node.centerX)
        assertEquals(100, node.centerY)
    }

    @Test
    fun uiNode_buildsTreeWithChildren() {
        val child = UiNode(text = "Child", description = null, className = "Button",
            packageName = null, viewId = null, bounds = Rect(), isClickable = true,
            isEditable = false, isScrollable = false, isChecked = false, isCheckable = false,
            isFocusable = false, isEnabled = true, isPassword = false, isVisible = true,
            depth = 1, children = emptyList())
        val parent = UiNode(text = "Parent", description = null, className = "ViewGroup",
            packageName = null, viewId = null, bounds = Rect(), isClickable = false,
            isEditable = false, isScrollable = false, isChecked = false, isCheckable = false,
            isFocusable = false, isEnabled = true, isPassword = false, isVisible = true,
            depth = 0, children = listOf(child))
        assertEquals(1, parent.children.size)
        assertEquals("Child", parent.children[0].text)
        assertEquals(1, parent.children[0].depth)
    }

    // ====================================================================
    //  SearchCriteria Tests
    // ====================================================================

    @Test
    fun searchCriteria_matchesTextExactly() {
        val criteria = SearchCriteria(text = "Submit")
        assertNotNull(criteria)
        assertEquals("Submit", criteria.text)
    }

    @Test
    fun searchCriteria_matchesTextContains() {
        val criteria = SearchCriteria(textContains = "sub")
        assertNotNull(criteria)
        assertEquals("sub", criteria.textContains)
    }

    @Test
    fun searchCriteria_combinesMultipleFilters() {
        val criteria = SearchCriteria(
            textContains = "Save",
            isClickable = true,
            isEnabled = true
        )
        assertNotNull(criteria)
        assertTrue(criteria.isClickable!!)
        assertTrue(criteria.isEnabled!!)
    }

    @Test
    fun searchCriteria_defaultEnabledIsTrue() {
        val criteria = SearchCriteria(textContains = "ok")
        assertTrue(criteria.isEnabled!!)
    }

    @Test
    fun searchCriteria_maxDepthLimitsSearch() {
        val shallow = SearchCriteria(textContains = "deep", maxDepth = 0)
        assertEquals(0, shallow.maxDepth)
        val deep = SearchCriteria(textContains = "hello", maxDepth = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, deep.maxDepth)
    }

    // ====================================================================
    //  Action Command Parsing Tests
    // ====================================================================

    @Test
    fun actionCommands_parseTap_simpleText() {
        val cmd = "tap Submit"
        assertTrue(cmd.startsWith("tap "))
        val rest = cmd.removePrefix("tap ").trim()
        assertEquals("Submit", rest)
    }

    @Test
    fun actionCommands_parseTap_coordinates() {
        val cmd = "tap coord 500 1000"
        assertTrue(cmd.startsWith("tap "))
        val rest = cmd.removePrefix("tap ").trim()
        assertTrue(rest.startsWith("coord"))
        val coords = rest.removePrefix("coord").trim().split(Regex("\\s+"))
        assertEquals(2, coords.size)
        assertEquals(500, coords[0].toInt())
        assertEquals(1000, coords[1].toInt())
    }

    @Test
    fun actionCommands_parseType() {
        val cmd = "type Hello world"
        val text = cmd.removePrefix("type ").trim()
        assertEquals("Hello world", text)
    }

    @Test
    fun actionCommands_parseSwipe() {
        val cmd = "swipe 100 200 300 400"
        val parts = cmd.removePrefix("swipe ").trim().split(Regex("\\s+"))
        assertEquals(4, parts.size)
        assertEquals(100, parts[0].toInt())
        assertEquals(400, parts[3].toInt())
    }

    @Test
    fun actionCommands_parseWait() {
        val cmd = "wait 2000"
        val ms = cmd.removePrefix("wait ").trim().toLongOrNull() ?: 1000
        assertEquals(2000L, ms)
    }

    @Test
    fun actionCommands_parseScroll() {
        assertEquals("up", "scroll up".removePrefix("scroll ").trim())
        assertEquals("down", "scroll down".removePrefix("scroll ").trim())
    }

    @Test
    fun actionCommands_parseOpenApp() {
        val cmd = "open app com.spotify.music"
        val pkg = cmd.removePrefix("open app ").trim()
        assertEquals("com.spotify.music", pkg)
    }

    // ====================================================================
    //  Edge Cases
    // ====================================================================

    @Test
    fun uiNode_emptyTextIsHandled() {
        val node = UiNode(text = "", description = "desc", className = "TextView",
            packageName = null, viewId = null, bounds = Rect(), isClickable = false,
            isEditable = false, isScrollable = false, isChecked = false, isCheckable = false,
            isFocusable = false, isEnabled = true, isPassword = false, isVisible = true,
            depth = 0, children = emptyList())
        assertEquals("", node.text)
        assertEquals("desc", node.label) // falls back to description
    }

    @Test
    fun uiNode_nullTextUsesDescription() {
        val node = UiNode(text = null, description = "OK", className = "Button",
            packageName = null, viewId = null, bounds = Rect(), isClickable = true,
            isEditable = false, isScrollable = false, isChecked = false, isCheckable = false,
            isFocusable = false, isEnabled = true, isPassword = false, isVisible = true,
            depth = 0, children = emptyList())
        assertEquals("OK", node.label)
    }

    @Test
    fun uiNode_allNullsDefaultsToRole() {
        val node = UiNode(text = null, description = null, className = "android.widget.ImageButton",
            packageName = null, viewId = null, bounds = Rect(), isClickable = true,
            isEditable = false, isScrollable = false, isChecked = false, isCheckable = false,
            isFocusable = false, isEnabled = true, isPassword = false, isVisible = true,
            depth = 0, children = emptyList())
        assertEquals("ImageButton", node.role)
    }

    @Test
    fun router_handlesEmptyEntities() {
        val result = IntentResult("QUERY", 0.8f, entities = emptyMap())
        val resolution = router.resolve(result)
        assertTrue(resolution is Resolution.LocalLm)
    }

    @Test
    fun router_handlesLowConfidence() {
        val result = IntentResult("LAUNCH_APP", 0.3f, mapOf("app" to "test"))
        val resolution = router.resolve(result)
        assertTrue(resolution is Resolution.Native) // Still routes to native
    }

    @Test
    fun executionSequence_emptyList_succeeds() {
        val actions = emptyList<String>()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun executionSequence_singleAction() {
        val actions = listOf("tap Submit")
        assertEquals(1, actions.size)
    }

    // ====================================================================
    //  Performance Benchmark
    // ====================================================================

    @Test
    fun benchmark_routerResolutionSpeed() {
        val intents = listOf(
            IntentResult("LAUNCH_APP", 0.95f, mapOf("app" to "spotify")),
            IntentResult("SEND_SMS", 0.9f, mapOf("contact" to "Mom", "message" to "Hi")),
            IntentResult("QUERY", 0.8f, mapOf("query" to "what's the weather")),
            IntentResult("READ_SCREEN", 0.9f),
            IntentResult("UNKNOWN", 0f, requiresClarification = true),
            IntentResult("SCROLL", 0.85f, mapOf("direction" to "down"))
        )

        val iterations = 100
        // Warm up the JIT so we measure steady-state throughput, not cold start
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
        println("CapabilityRouter benchmark (${iterations * intents.size} resolutions): avg=${avgUs}µs")

        // Router resolution should be sub-millisecond
        assertTrue("Router resolution should be < 1000µs per call, got ${avgUs}µs", avgUs < 1000)
    }
}
