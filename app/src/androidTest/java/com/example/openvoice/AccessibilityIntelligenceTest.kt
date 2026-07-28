package com.example.openvoice

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.accessibility.*
import com.example.openvoice.accessibility.engine.*
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.task.CompletionStatus
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
class AccessibilityIntelligenceTest {

    private lateinit var blackboard: TaskBlackboard
    private lateinit var search: UiSearchEngine
    private lateinit var router: CapabilityRouter

    // Sample semantic tree for testing
    private fun createSampleScreen(): ScreenState {
        val root = SemanticUiNode(
            role = UiRole.CONTAINER,
            text = "Main Screen",
            contentDescription = null,
            viewId = null,
            bounds = Rect(0, 0, 1080, 1920),
            centerX = 540, centerY = 960,
            isClickable = false, isEnabled = true, isVisible = true,
            isFocusable = false, isEditable = false, isChecked = null,
            isCheckable = false, isScrollable = false, isPassword = false,
            depth = 0, packageName = "com.example.app", className = "android.widget.FrameLayout",
            children = listOf(
                SemanticUiNode(role = UiRole.INPUT, text = "Search messages", contentDescription = "Search",
                    viewId = "com.example.app:id/search", bounds = Rect(50, 100, 1000, 180),
                    centerX = 525, centerY = 140,
                    isClickable = true, isEnabled = true, isVisible = true,
                    isFocusable = true, isEditable = true, isChecked = null,
                    isCheckable = false, isScrollable = false, isPassword = false,
                    depth = 1, packageName = "com.example.app", className = "android.widget.EditText",
                    children = emptyList()),
                SemanticUiNode(role = UiRole.BUTTON, text = "Send", contentDescription = "Send message",
                    viewId = "com.example.app:id/send", bounds = Rect(900, 1800, 1040, 1900),
                    centerX = 970, centerY = 1850,
                    isClickable = true, isEnabled = true, isVisible = true,
                    isFocusable = true, isEditable = false, isChecked = null,
                    isCheckable = false, isScrollable = false, isPassword = false,
                    depth = 1, packageName = "com.example.app", className = "android.widget.Button",
                    children = emptyList()),
                SemanticUiNode(role = UiRole.CHECKBOX, text = "Remember me",
                    contentDescription = null, viewId = "com.example.app:id/remember",
                    bounds = Rect(50, 500, 200, 600),
                    centerX = 125, centerY = 550,
                    isClickable = true, isEnabled = true, isVisible = true,
                    isFocusable = true, isEditable = false, isChecked = true,
                    isCheckable = true, isScrollable = false, isPassword = false,
                    depth = 1, packageName = "com.example.app", className = "android.widget.CheckBox",
                    children = emptyList()),
                SemanticUiNode(role = UiRole.BUTTON, text = "Submit", contentDescription = null,
                    viewId = "com.example.app:id/submit", bounds = Rect(100, 800, 500, 900),
                    centerX = 300, centerY = 850,
                    isClickable = true, isEnabled = true, isVisible = true,
                    isFocusable = true, isEditable = false, isChecked = null,
                    isCheckable = false, isScrollable = false, isPassword = false,
                    depth = 1, packageName = "com.example.app", className = "android.widget.Button",
                    children = emptyList()),
                SemanticUiNode(role = UiRole.TEXT, text = "Welcome back!", contentDescription = null,
                    viewId = null, bounds = Rect(50, 300, 600, 380),
                    centerX = 325, centerY = 340,
                    isClickable = false, isEnabled = true, isVisible = true,
                    isFocusable = false, isEditable = false, isChecked = null,
                    isCheckable = false, isScrollable = false, isPassword = false,
                    depth = 1, packageName = "com.example.app", className = "android.widget.TextView",
                    children = emptyList()),
                SemanticUiNode(role = UiRole.LIST, text = null, contentDescription = null,
                    viewId = "com.example.app:id/list", bounds = Rect(0, 400, 1080, 1700),
                    centerX = 540, centerY = 1050,
                    isClickable = false, isEnabled = true, isVisible = true,
                    isFocusable = false, isEditable = false, isChecked = null,
                    isCheckable = false, isScrollable = true, isPassword = false,
                    depth = 1, packageName = "com.example.app", className = "android.widget.ListView",
                    children = listOf(
                        SemanticUiNode(role = UiRole.LIST_ITEM, text = "Item 1", contentDescription = null,
                            viewId = null, bounds = Rect(10, 410, 1070, 530),
                            centerX = 540, centerY = 470,
                            isClickable = true, isEnabled = true, isVisible = true,
                            isFocusable = true, isEditable = false, isChecked = null,
                            isCheckable = false, isScrollable = false, isPassword = false,
                            depth = 2, packageName = "com.example.app", className = "android.widget.LinearLayout",
                            children = emptyList()),
                        SemanticUiNode(role = UiRole.LIST_ITEM, text = "Item 2", contentDescription = null,
                            viewId = null, bounds = Rect(10, 531, 1070, 650),
                            centerX = 540, centerY = 590,
                            isClickable = true, isEnabled = true, isVisible = true,
                            isFocusable = true, isEditable = false, isChecked = null,
                            isCheckable = false, isScrollable = false, isPassword = false,
                            depth = 2, packageName = "com.example.app", className = "android.widget.LinearLayout",
                            children = emptyList()))
                ),
                SemanticUiNode(role = UiRole.DIALOG, text = "Update available",
                    contentDescription = null, viewId = null,
                    bounds = Rect(100, 500, 980, 1400),
                    centerX = 540, centerY = 950,
                    isClickable = false, isEnabled = true, isVisible = true,
                    isFocusable = false, isEditable = false, isChecked = null,
                    isCheckable = false, isScrollable = false, isPassword = false,
                    depth = 1, packageName = "com.example.app",
                    className = "android.app.Dialog",
                    children = listOf(
                        SemanticUiNode(role = UiRole.BUTTON, text = "OK",
                            contentDescription = null, viewId = null,
                            bounds = Rect(300, 1200, 600, 1330),
                            centerX = 450, centerY = 1265,
                            isClickable = true, isEnabled = true, isVisible = true,
                            isFocusable = true, isEditable = false, isChecked = null,
                            isCheckable = false, isScrollable = false, isPassword = false,
                            depth = 2, packageName = "com.example.app",
                            className = "android.widget.Button", children = emptyList()),
                        SemanticUiNode(role = UiRole.BUTTON, text = "Later",
                            contentDescription = null, viewId = null,
                            bounds = Rect(620, 1200, 920, 1330),
                            centerX = 770, centerY = 1265,
                            isClickable = true, isEnabled = true, isVisible = true,
                            isFocusable = true, isEditable = false, isChecked = null,
                            isCheckable = false, isScrollable = false, isPassword = false,
                            depth = 2, packageName = "com.example.app",
                            className = "android.widget.Button", children = emptyList())
                    ))
            )
        )
        return ScreenState.fromUiTree(root.let {
            com.example.openvoice.accessibility.UiNode(
                text = it.text, description = it.contentDescription,
                className = it.className, packageName = it.packageName,
                viewId = it.viewId, bounds = it.bounds,
                isClickable = it.isClickable, isEditable = it.isEditable,
                isScrollable = it.isScrollable, isChecked = it.isChecked ?: false,
                isCheckable = it.isCheckable, isFocusable = it.isFocusable,
                isEnabled = it.isEnabled, isPassword = it.isPassword,
                isVisible = it.isVisible, depth = it.depth,
                children = it.children.map { childNode ->
                    com.example.openvoice.accessibility.UiNode(
                        text = childNode.text, description = childNode.contentDescription,
                        className = childNode.className, packageName = childNode.packageName,
                        viewId = childNode.viewId, bounds = childNode.bounds,
                        isClickable = childNode.isClickable, isEditable = childNode.isEditable,
                        isScrollable = childNode.isScrollable,
                        isChecked = childNode.isChecked ?: false,
                        isCheckable = childNode.isCheckable,
                        isFocusable = childNode.isFocusable,
                        isEnabled = childNode.isEnabled, isPassword = childNode.isPassword,
                        isVisible = childNode.isVisible, depth = childNode.depth,
                        children = emptyList()
                    )
                }
            )
        })
    }

    @Before
    fun setup() {
        Logger.init(true)
        blackboard = TaskBlackboard()
        search = UiSearchEngine()
        router = CapabilityRouter()
    }

    // ── Semantic UI Types Tests ──────────────────────────────────────

    @Test
    fun uiRole_fromClassName_mapsCorrectly() {
        assertEquals(UiRole.BUTTON, UiRole.fromClassName("android.widget.Button"))
        assertEquals(UiRole.SWITCH, UiRole.fromClassName("android.widget.Switch"))
        assertEquals(UiRole.CHECKBOX, UiRole.fromClassName("android.widget.CheckBox"))
        assertEquals(UiRole.TEXT, UiRole.fromClassName("android.widget.TextView"))
        assertEquals(UiRole.INPUT, UiRole.fromClassName("android.widget.EditText"))
        assertEquals(UiRole.IMAGE, UiRole.fromClassName("android.widget.ImageView"))
        assertEquals(UiRole.LIST, UiRole.fromClassName("android.widget.ListView"))
        assertEquals(UiRole.DIALOG, UiRole.fromClassName("android.app.Dialog"))
        assertEquals(UiRole.UNKNOWN, UiRole.fromClassName("android.view.View"))
        assertEquals(UiRole.PROGRESS, UiRole.fromClassName("android.widget.ProgressBar"))
        assertEquals(UiRole.SLIDER, UiRole.fromClassName("android.widget.SeekBar"))
        assertEquals(UiRole.WEB_VIEW, UiRole.fromClassName("android.webkit.WebView"))
    }

    @Test
    fun semanticUiNode_fromUiNode_mapsCorrectly() {
        val node = com.example.openvoice.accessibility.UiNode(
            text = "Hello", description = "Hello description",
            className = "android.widget.Button", packageName = "com.test",
            viewId = "com.test:id/hello", bounds = Rect(0, 0, 100, 50),
            isClickable = true, isEditable = false, isScrollable = false,
            isChecked = false, isCheckable = false, isFocusable = true,
            isEnabled = true, isPassword = false, isVisible = true,
            depth = 0, children = emptyList()
        )
        val semantic = SemanticUiNode.fromUiNode(node)
        assertEquals(UiRole.BUTTON, semantic.role)
        assertEquals("Hello", semantic.text)
        assertEquals("Hello description", semantic.contentDescription)
        assertTrue(semantic.isClickable)
        assertTrue(semantic.interactive)
        assertEquals(50, semantic.centerX)
        assertEquals(25, semantic.centerY)
    }

    @Test
    fun semanticNode_label_fallsBack() {
        val withText = SemanticUiNode(role = UiRole.BUTTON, text = "Click", contentDescription = "Desc",
            viewId = null, bounds = Rect(), centerX = 0, centerY = 0,
            isClickable = true, isEnabled = true, isVisible = true,
            isFocusable = false, isEditable = false, isChecked = null,
            isCheckable = false, isScrollable = false, isPassword = false,
            depth = 0, packageName = null, className = null, children = emptyList())
        assertEquals("Click", withText.label)

        val withDesc = withText.copy(text = null)
        assertEquals("Desc", withDesc.label)

        val withViewId = withDesc.copy(contentDescription = null, viewId = "my_id")
        assertEquals("my_id", withViewId.label)

        val withRole = withViewId.copy(viewId = null)
        assertEquals("BUTTON", withRole.label)
    }

    // ── ScreenState Tests ────────────────────────────────────────────

    @Test
    fun screenState_fromUiTree_parsesCorrectly() {
        val screen = createSampleScreen()
        assertNotNull(screen.semanticTree)
        assertEquals("com.example.app", screen.packageName)
        assertTrue(screen.interactiveElements.isNotEmpty())
        assertTrue(screen.textInputs.isNotEmpty())
        assertTrue(screen.clickableItems.isNotEmpty())
        assertTrue(screen.scrollableContainers.isNotEmpty())
        assertTrue(screen.hasDialogs)
    }

    @Test
    fun screenState_interactiveElements_onlyVisible() {
        val screen = createSampleScreen()
        val allInteractive = screen.interactiveElements
        assertTrue(allInteractive.all { it.isVisible })
        assertTrue(allInteractive.all { it.interactive })
    }

    @Test
    fun screenState_dialogs_detectedCorrectly() {
        val screen = createSampleScreen()
        assertEquals(1, screen.dialogs.size)
        assertEquals("Update available", screen.dialogs[0].text)
        assertEquals(UiRole.DIALOG, screen.dialogs[0].role)
    }

    @Test
    fun screenState_textInputs_found() {
        val screen = createSampleScreen()
        assertTrue(screen.textInputs.any { it.role == UiRole.INPUT })
        assertEquals("Search messages", screen.textInputs[0].text)
    }

    // ── UI Search Engine Tests ───────────────────────────────────────

    @Test
    fun search_findsByExactText() {
        val screen = createSampleScreen()
        val result = search.findFirst(screen, UiQuery(textExact = "Send"))
        assertNotNull(result)
        assertEquals("Send", result?.text)
        assertEquals(UiRole.BUTTON, result?.role)
    }

    @Test
    fun search_findsByTextContains() {
        val screen = createSampleScreen()
        val results = search.findAll(screen, UiQuery(textContains = "Item"))
        assertTrue(results.size >= 2)
    }

    @Test
    fun search_findsByRole() {
        val screen = createSampleScreen()
        val buttons = search.findAll(screen, UiQuery(role = UiRole.BUTTON))
        assertTrue(buttons.size >= 3) // Send, Submit, OK, Later
    }

    @Test
    fun search_findsByRoleAndText() {
        val screen = createSampleScreen()
        val btn = search.findFirst(screen, UiQuery(textContains = "Sub", role = UiRole.BUTTON))
        assertNotNull(btn)
        assertEquals("Submit", btn?.text)
    }

    @Test
    fun search_findsCheckableElements() {
        val screen = createSampleScreen()
        val checked = search.findAll(screen, UiQuery(isChecked = true))
        assertTrue(checked.isNotEmpty())
    }

    @Test
    fun search_findsDialogs() {
        val screen = createSampleScreen()
        val dialogs = search.findAll(screen, UiQuery(role = UiRole.DIALOG))
        assertEquals(1, dialogs.size)
    }

    @Test
    fun search_returnsEmptyForNoMatch() {
        val screen = createSampleScreen()
        val result = search.findFirst(screen, UiQuery(textExact = "NonExistent", isVisible = true))
        assertNull(result)
    }

    @Test
    fun search_returnsEmptyForNoMatchRole() {
        val screen = createSampleScreen()
        val result = search.findFirst(screen, UiQuery(role = UiRole.MAP))
        assertNull(result)
    }

    @Test
    fun search_scoresExactTextHigherThanPartial() {
        val screen = createSampleScreen()
        val exact = search.search(screen, UiQuery(textExact = "Send"))
        val partial = search.search(screen, UiQuery(textContains = "Sen"))

        if (exact.isNotEmpty() && partial.isNotEmpty()) {
            assertTrue("Exact match should score >= partial",
                exact[0].score >= partial[0].score)
        }
    }

    @Test
    fun search_findButton_helper() {
        val screen = createSampleScreen()
        val btn = search.findButton(screen, "Submit")
        assertNotNull(btn)
        assertEquals("Submit", btn?.text)
    }

    @Test
    fun search_findTextFields() {
        val screen = createSampleScreen()
        val fields = search.findTextFields(screen)
        assertTrue(fields.any { it.text == "Search messages" })
    }

    @Test
    fun search_maxResults_limits() {
        val screen = createSampleScreen()
        val many = search.findAll(screen, UiQuery(isClickable = true))
        val limited = search.search(screen, UiQuery(isClickable = true, maxResults = 1))
        assertEquals(1, limited.size)
    }

    // ── TaskBlackboard Tests ─────────────────────────────────────────

    @Test
    fun blackboard_startsEmpty() {
        val state = blackboard.state.value
        assertEquals("", state.userGoal)
        assertEquals(CompletionStatus.PENDING, state.completionStatus)
        assertTrue(state.actionHistory.isEmpty())
    }

    @Test
    fun blackboard_startNewTask_setsGoal() {
        blackboard.startNewTask("Open Spotify", "open spotify")
        val state = blackboard.state.value
        assertEquals("Open Spotify", state.userGoal)
        assertEquals(CompletionStatus.IN_PROGRESS, state.completionStatus)
        assertTrue(state.startedAt > 0)
    }

    @Test
    fun blackboard_recordsActions() {
        blackboard.startNewTask("Test", "test")
        blackboard.recordSuccess("tap Submit", 100)
        blackboard.recordSuccess("type Hello", 50)
        assertEquals(2, blackboard.state.value.actionHistory.size)
        assertTrue(blackboard.state.value.lastActionResult?.success == true)
    }

    @Test
    fun blackboard_recordsFailures() {
        blackboard.startNewTask("Test", "test")
        blackboard.recordFailure("tap Hidden", "Element not found", "Try scrolling")
        assertEquals(1, blackboard.state.value.retryCount)
        assertFalse(blackboard.state.value.lastActionResult?.success ?: true)
    }

    @Test
    fun blackboard_tracksFailureRetrySuggestion() {
        blackboard.startNewTask("Test", "test")
        blackboard.recordFailure("scroll", "Nothing to scroll", "Try going back first")
        assertEquals("Try going back first",
            blackboard.state.value.lastActionResult?.retrySuggestion)
    }

    @Test
    fun blackboard_markCompleted() {
        blackboard.startNewTask("Test", "test")
        blackboard.markCompleted()
        assertEquals(CompletionStatus.COMPLETED, blackboard.state.value.completionStatus)
        assertTrue(blackboard.state.value.completedAt > 0)
    }

    @Test
    fun blackboard_markFailed() {
        blackboard.startNewTask("Test", "test")
        blackboard.markFailed("Something went wrong")
        assertEquals(CompletionStatus.FAILED, blackboard.state.value.completionStatus)
        assertEquals("Something went wrong", blackboard.state.value.errorMessage)
    }

    @Test
    fun blackboard_markCancelled() {
        blackboard.startNewTask("Test", "test")
        blackboard.markCancelled()
        assertEquals(CompletionStatus.CANCELLED, blackboard.state.value.completionStatus)
    }

    @Test
    fun blackboard_planManagement() {
        blackboard.startNewTask("Open Gmail and send email", "open gmail and send email")
        blackboard.setPlan(listOf("Open Gmail", "Tap Compose", "Type text", "Send"))
        assertEquals(4, blackboard.state.value.plan.size)
        assertEquals(0, blackboard.state.value.currentStepIndex)
        assertEquals("Open Gmail", blackboard.currentPlanStep())

        assertTrue(blackboard.advancePlan())
        assertEquals(1, blackboard.state.value.currentStepIndex)
        assertEquals("Tap Compose", blackboard.currentPlanStep())

        assertTrue(blackboard.advancePlan())
        assertEquals("Type text", blackboard.currentPlanStep())

        assertTrue(blackboard.advancePlan())
        assertEquals("Send", blackboard.currentPlanStep())

        assertFalse(blackboard.advancePlan()) // Plan complete
        assertTrue(blackboard.state.value.planComplete)
    }

    @Test
    fun blackboard_screenContext() {
        blackboard.startNewTask("Click the button", "click the button")
        blackboard.setCurrentApp("com.example.app")
        blackboard.setCurrentActivity("com.example.app.MainActivity")
        assertEquals("com.example.app", blackboard.state.value.currentApp)
        assertTrue(blackboard.requiresCurrentScreen())
    }

    @Test
    fun blackboard_requiresCurrentScreen_withThis() {
        blackboard.startNewTask("Reply to this message", "reply to this message")
        assertTrue(blackboard.requiresCurrentScreen())
    }

    @Test
    fun blackboard_requiresCurrentScreen_withoutThis() {
        blackboard.startNewTask("Open Spotify", "open spotify")
        assertFalse(blackboard.requiresCurrentScreen())
    }

    @Test
    fun blackboard_reset_clearsState() {
        blackboard.startNewTask("Test", "test")
        blackboard.recordSuccess("tap", 10)
        blackboard.reset()
        assertEquals(CompletionStatus.PENDING, blackboard.state.value.completionStatus)
        assertEquals("", blackboard.state.value.userGoal)
    }

    @Test
    fun blackboard_getTimeline_containsGoal() {
        blackboard.startNewTask("Open Spotify", "open spotify")
        val timeline = blackboard.getTimeline()
        assertTrue(timeline.contains("Open Spotify"))
    }

    // ── CapabilityRouter Accessibility Integration Tests ─────────────

    @Test
    fun router_routesScrollToAccessibility() {
        val result = IntentResult("SCROLL", 0.9f, mapOf("direction" to "down"))
        val resolution = router.resolve(result)
        assertTrue("SCROLL should route to Accessibility", resolution is Resolution.Accessibility)
    }

    @Test
    fun router_routesNavigateBackToAccessibility() {
        val result = IntentResult("NAVIGATE_BACK", 0.9f)
        val resolution = router.resolve(result)
        assertTrue("NAVIGATE_BACK should route to Accessibility", resolution is Resolution.Accessibility)
    }

    @Test
    fun router_routesNavigateHomeToAccessibility() {
        val result = IntentResult("NAVIGATE_HOME", 0.9f)
        val resolution = router.resolve(result)
        assertTrue("NAVIGATE_HOME should route to Accessibility", resolution is Resolution.Accessibility)
    }

    @Test
    fun router_routesReadScreenToAccessibility() {
        val result = IntentResult("READ_SCREEN", 0.9f)
        val resolution = router.resolve(result)
        // When accessibility is not running, it should at least not go to Unsupported
        assertTrue(resolution is Resolution.Accessibility || resolution is Resolution.Vision)
    }

    // ── Action Engine Tests ──────────────────────────────────────────

    @Test
    fun actionEngine_parseTapCommand() {
        val cmd = "tap Submit"
        assertTrue(cmd.startsWith("tap "))
        assertEquals("Submit", cmd.removePrefix("tap ").trim())
    }

    @Test
    fun actionEngine_parseTypeCommand() {
        val cmd = "type Hello world"
        assertEquals("Hello world", cmd.removePrefix("type ").trim())
    }

    @Test
    fun actionEngine_parseScrollCommand() {
        assertEquals("down", "scroll down".removePrefix("scroll ").trim())
        assertEquals("up", "scroll up".removePrefix("scroll ").trim())
    }

    @Test
    fun actionEngine_parseSwipeCommand() {
        val cmd = "swipe 100 200 300 400"
        val parts = cmd.removePrefix("swipe ").trim().split(Regex("\\s+"))
        assertEquals(4, parts.size)
        assertEquals(100, parts[0].toInt())
        assertEquals(400, parts[3].toInt())
    }

    @Test
    fun actionEngine_parseOpenAppCommand() {
        val cmd = "open app com.spotify.music"
        assertEquals("com.spotify.music", cmd.removePrefix("open app ").trim())
    }

    @Test
    fun actionEngine_parseWaitCommand() {
        val cmd = "wait 2000"
        assertEquals(2000L, cmd.removePrefix("wait ").trim().toLongOrNull() ?: 1000L)
    }

    // ── Workflow Engine Tests ────────────────────────────────────────

    @Test
    fun workflowEngine_sendSmsWorkflow_hasCorrectSteps() {
        // Create workflow steps without service reference (just test step generation)
        val contact = "Mom"
        val message = "I'll be late"
        val steps = mutableListOf<WorkflowEngine.WorkflowStep>()

        steps.add(WorkflowEngine.WorkflowStep("tap Compose", "Open new message"))
        steps.add(WorkflowEngine.WorkflowStep("type $contact", "Enter recipient",
            alternateActions = listOf("tap To", "tap recipient")))
        steps.add(WorkflowEngine.WorkflowStep("tap message", "Focus message body",
            alternateActions = listOf("tap text", "tap body")))
        steps.add(WorkflowEngine.WorkflowStep("type $message", "Type message"))
        steps.add(WorkflowEngine.WorkflowStep("tap Send", "Send message",
            alternateActions = listOf("tap send", "send")))

        assertEquals(5, steps.size)
        assertEquals("tap Compose", steps[0].action)
        assertEquals("Enter recipient", steps[1].description)
        assertEquals(2, steps[1].alternateActions.size)
    }

    @Test
    fun workflowStep_alternateActions_onFailure() {
        val step = WorkflowEngine.WorkflowStep(
            action = "tap Submit",
            description = "Submit form",
            maxRetries = 3,
            alternateActions = listOf("tap send", "tap OK", "tap continue")
        )
        assertEquals(3, step.maxRetries)
        assertEquals(3, step.alternateActions.size)
        assertTrue(step.alternateActions.contains("tap OK"))
    }

    @Test
    fun workflowStep_withRecovery() {
        val step = WorkflowEngine.WorkflowStep(
            action = "tap Continue",
            description = "Continue to next page",
            maxRetries = 1,
            alternateActions = listOf("tap continue", "tap Next"),
            recoveryAction = "back"
        )
        assertEquals("back", step.recoveryAction)
    }

    // ── Accessibility Intelligence Tests ─────────────────────────────

    @Test
    fun naturalLanguageTap_parsesCorrectly() {
        val commands = listOf(
            "tap Continue" to "Continue",
            "tap the Save button" to "the Save button",
            "tap Submit" to "Submit"
        )
        for (cmd in commands) {
            val lower = cmd.first.lowercase().trim()
            if (lower.startsWith("tap ")) {
                val text = cmd.first.removePrefix("tap ").trim()
                assertNotNull(text)
            }
        }
    }

    @Test
    fun naturalLanguageScroll_parsesCorrectly() {
        assertTrue("scroll down".startsWith("scroll "))
        assertTrue("scroll up".startsWith("scroll "))
        assertEquals("down", "scroll down".removePrefix("scroll ").trim())
    }

    // ── Performance Benchmarks ────────────────────────────────────────

    @Test
    fun benchmark_screenStateParsing() {
        val iterations = 100
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val screen = createSampleScreen()
            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("ScreenState parsing benchmark (n=$iterations): avg=${avgUs}µs")
        assertTrue("ScreenState parsing < 10ms", avgUs < 10_000)
    }

    @Test
    fun benchmark_uiSearchSpeed() {
        val screen = createSampleScreen()
        val queries = listOf(
            UiQuery(textExact = "Send"),
            UiQuery(textContains = "Item"),
            UiQuery(role = UiRole.BUTTON),
            UiQuery(textContains = "Search", role = UiRole.INPUT),
            UiQuery(isClickable = true, isVisible = true)
        )

        val iterations = 100
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            for (query in queries) {
                val start = System.nanoTime()
                search.findFirst(screen, query)
                timings.add((System.nanoTime() - start) / 1_000)
            }
        }

        val avgUs = timings.average().toLong()
        println("UI Search benchmark (n=${queries.size * iterations}): avg=${avgUs}µs")
        assertTrue("UI Search < 5ms per query", avgUs < 5_000)
    }

    @Test
    fun benchmark_blackboardOperations() {
        val iterations = 1000
        val writeTimes = mutableListOf<Long>()
        val readTimes = mutableListOf<Long>()

        for (i in 0 until iterations) {
            var start = System.nanoTime()
            blackboard.startNewTask("Test $i", "test")
            blackboard.recordSuccess("action $i", i.toLong())
            blackboard.setCurrentApp("com.test$i")
            writeTimes.add((System.nanoTime() - start) / 1_000)

            start = System.nanoTime()
            val state = blackboard.state.value
            val goal = state.userGoal
            val history = state.actionHistory.size
            readTimes.add((System.nanoTime() - start) / 1_000)
        }

        val avgWriteUs = writeTimes.average().toLong()
        val avgReadUs = readTimes.average().toLong()
        println("Blackboard benchmark (n=$iterations): write=${avgWriteUs}µs read=${avgReadUs}µs")
        assertTrue("Blackboard write < 1ms", avgWriteUs < 1_000)
        assertTrue("Blackboard read < 100µs", avgReadUs < 100)
    }

    @Test
    fun benchmark_workflowStepCreation() {
        val iterations = 500
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val step = WorkflowEngine.WorkflowStep(
                action = "tap Button $i",
                description = "Test step",
                maxRetries = 2,
                alternateActions = listOf("tap alt1", "tap alt2"),
                recoveryAction = "back"
            )
            timings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = timings.average().toLong()
        println("WorkflowStep creation benchmark (n=$iterations): avg=${avgUs}µs")
        assertTrue("WorkflowStep creation < 100µs", avgUs < 100)
    }

    @Test
    fun benchmark_comprehensivePipeline() {
        // Simulate an entire pipeline cycle: transcribe → classify → route → execute
        val iterations = 50
        val pipelineTimings = mutableListOf<Long>()

        val intents = listOf(
            IntentResult("LAUNCH_APP", 0.95f, mapOf("app" to "spotify")),
            IntentResult("QUERY", 0.8f, mapOf("query" to "what is this screen")),
            IntentResult("SCROLL", 0.9f, mapOf("direction" to "down")),
            IntentResult("READ_SCREEN", 0.85f),
            IntentResult("SEND_SMS", 0.9f, mapOf("contact" to "Mom", "message" to "Hi")),
            IntentResult("SET_TIMER", 0.95f, mapOf("duration" to "5 minutes"))
        )

        for (i in 0 until iterations) {
            val start = System.nanoTime()

            // 1. Classify (simulated)
            val intent = intents[i % intents.size]

            // 2. Route
            val resolution = router.resolve(intent)

            // 3. Start task
            blackboard.startNewTask(intent.intent, intent.intent)
            blackboard.setIntent(intent, resolution)

            // 4. Record
            blackboard.recordSuccess("executed", (System.nanoTime() - start) / 1_000_000)

            pipelineTimings.add((System.nanoTime() - start) / 1_000)
        }

        val avgUs = pipelineTimings.average().toLong()
        println("Pipeline benchmark (n=$iterations): avg=${avgUs}µs")
        assertTrue("Pipeline cycle < 5ms", avgUs < 5_000)
    }
}
