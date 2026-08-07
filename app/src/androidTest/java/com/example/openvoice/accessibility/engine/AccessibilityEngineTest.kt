package com.example.openvoice.accessibility.engine

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.accessibility.AccessibilityGateway
import com.example.openvoice.accessibility.UiNode
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

/**
 * Drives the real accessibility engine logic through a scripted fake
 * [AccessibilityGateway], so every action-execution, screen-parsing, and
 * workflow path is exercised deterministically without a live service.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityEngineTest {

    // ── Scripted Fake Gateway ───────────────────────────────────────

    private class FakeGateway : AccessibilityGateway {
        var tree: UiNode? = null
        var pkg: String? = "com.android.settings"
        var tapResult = true
        var longPressResult = true
        var typeResult = true
        var scrollResult = true
        var swipeResult = true
        var openAppResult = true
        var navResult = true
        var tapTextBehavior: (String) -> Boolean = { true }

        val tappedTexts = mutableListOf<String>()
        val tappedCoords = mutableListOf<Pair<Int, Int>>()
        val typedTexts = mutableListOf<String>()
        val scrollDirections = mutableListOf<String>()
        val swiped = mutableListOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        val openedApps = mutableListOf<String>()
        var backCalls = 0
        var homeCalls = 0
        var recentsCalls = 0
        var notifCalls = 0
        var qsCalls = 0
        var longPressCalls = 0

        override fun getUiTree(): UiNode? = tree
        override fun getActivePackage(): String? = pkg

        override suspend fun tap(x: Int, y: Int): Boolean {
            tappedCoords.add(x to y)
            return tapResult
        }

        override suspend fun tapText(text: String): Boolean {
            tappedTexts.add(text)
            return tapTextBehavior(text)
        }

        override suspend fun longPress(x: Int, y: Int): Boolean {
            longPressCalls++
            return longPressResult
        }

        override suspend fun type(text: String): Boolean {
            typedTexts.add(text)
            return typeResult
        }

        override suspend fun scroll(direction: String): Boolean {
            scrollDirections.add(direction)
            return scrollResult
        }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            swiped.add((x1 to y1) to (x2 to y2))
            return swipeResult
        }

        override suspend fun goBack(): Boolean { backCalls++; return navResult }
        override suspend fun goHome(): Boolean { homeCalls++; return navResult }
        override suspend fun openRecents(): Boolean { recentsCalls++; return navResult }
        override suspend fun openNotifications(): Boolean { notifCalls++; return navResult }
        override suspend fun openQuickSettings(): Boolean { qsCalls++; return navResult }

        override suspend fun openApp(packageName: String): Boolean {
            openedApps.add(packageName)
            return openAppResult
        }
    }

    // ── Node builders ───────────────────────────────────────────────

    private fun node(
        text: String? = null,
        description: String? = null,
        className: String = "android.widget.Button",
        bounds: Rect = Rect(0, 0, 200, 100),
        clickable: Boolean = true,
        editable: Boolean = false,
        scrollable: Boolean = false,
        children: List<UiNode> = emptyList(),
        depth: Int = 0
    ) = UiNode(
        text = text, description = description, className = className,
        packageName = "com.android.settings", viewId = null, bounds = bounds,
        isClickable = clickable, isEditable = editable, isScrollable = scrollable,
        isChecked = false, isCheckable = false, isFocusable = false,
        isEnabled = true, isPassword = false, isVisible = true,
        depth = depth, children = children
    )

    private fun sampleTree(): UiNode {
        val dialog = node(text = "Update available", className = "android.app.Dialog",
            bounds = Rect(100, 500, 900, 1300), children = listOf(
                node(text = "OK", bounds = Rect(200, 1000, 450, 1150), depth = 1)
            ))
        return node(text = null, className = "android.widget.FrameLayout", clickable = false,
            bounds = Rect(0, 0, 1080, 1920), children = listOf(
                node(text = "Continue", depth = 1),
                node(text = "Search messages", className = "android.widget.EditText",
                    editable = true, bounds = Rect(50, 200, 1000, 280), depth = 1),
                node(text = null, className = "android.widget.RecyclerView", scrollable = true,
                    clickable = false, bounds = Rect(0, 400, 1080, 1600), depth = 1),
                dialog
            ))
    }

    private lateinit var gateway: FakeGateway
    private lateinit var blackboard: TaskBlackboard

    @Before
    fun setup() {
        Logger.init(true)
        gateway = FakeGateway()
        blackboard = TaskBlackboard()
    }

    // ── ScreenStateManager ──────────────────────────────────────────

    @Test
    fun screenManager_captureScreen_parsesTreeAndUpdatesBlackboard() = runBlocking {
        gateway.tree = sampleTree()
        val manager = ScreenStateManager(gateway, blackboard)

        val screen = manager.captureScreen()

        assertEquals("com.android.settings", screen.packageName)
        assertNotNull(screen.semanticTree)
        assertTrue(screen.interactiveElements.any { it.text == "Continue" })
        assertTrue(screen.textInputs.any { it.text == "Search messages" })
        assertTrue(screen.scrollableContainers.isNotEmpty())
        assertTrue(screen.hasDialogs)
        assertEquals("Update available", screen.dialogs.first().text)
        // Blackboard was updated with the parsed tree + app
        assertEquals("com.android.settings", blackboard.state.value.currentApp)
        assertNotNull(blackboard.state.value.uiTree)
    }

    @Test
    fun screenManager_captureScreen_emptyTree_isEmpty() = runBlocking {
        gateway.tree = null
        val manager = ScreenStateManager(gateway, blackboard)

        val screen = manager.captureScreen()

        assertTrue(screen.isEmpty)
        assertNull(screen.semanticTree)
        assertTrue(screen.interactiveElements.isEmpty())
        assertNull(manager.getTree())
    }

    @Test
    fun screenManager_tracksNavigationStack() = runBlocking {
        gateway.tree = sampleTree()
        val manager = ScreenStateManager(gateway, blackboard)

        manager.trackNavigation() // com.android.settings
        assertFalse(manager.canGoBack())
        assertEquals("com.android.settings", manager.getCurrentApp())

        gateway.pkg = "com.android.chrome"
        manager.trackNavigation() // com.android.chrome

        assertTrue(manager.canGoBack())
        assertEquals("com.android.chrome", manager.getCurrentApp())
        assertEquals("com.android.settings", manager.getPreviousApp())
        assertEquals(2, manager.getNavigationStack().size)

        manager.clearHistory()
        assertEquals(0, manager.getNavigationStack().size)
        assertFalse(manager.canGoBack())
    }

    @Test
    fun screenManager_trackNavigation_noPackage_returnsEarly() = runBlocking {
        gateway.pkg = null
        val manager = ScreenStateManager(gateway, blackboard)
        manager.trackNavigation()
        assertTrue(manager.getNavigationStack().isEmpty())
    }

    @Test
    fun screenManager_hasScreenChanged_detectsChanges() = runBlocking {
        gateway.tree = sampleTree()
        val manager = ScreenStateManager(gateway, blackboard)

        // First capture: nothing to compare against → changed
        assertTrue(manager.hasScreenChanged())
        // Identical second capture → unchanged
        assertFalse(manager.hasScreenChanged())

        // Different element count → changed
        gateway.tree = sampleTree().copy(children = sampleTree().children.drop(1))
        assertTrue(manager.hasScreenChanged())
    }

    @Test
    fun screenManager_detectDialogs_andKeyboard() = runBlocking {
        gateway.tree = sampleTree()
        val manager = ScreenStateManager(gateway, blackboard)

        assertFalse(manager.isKeyboardVisible())
        val dialogs = manager.detectDialogs()
        assertEquals(1, dialogs.size)
        assertEquals("Update available", dialogs.first().text)
    }

    @Test
    fun screenManager_waitForStableScreen_returnsTrueOnStableContent() = runBlocking {
        gateway.tree = sampleTree()
        val manager = ScreenStateManager(gateway, blackboard)

        // 3 consecutive identical captures → stable
        assertTrue(manager.waitForStableScreen(timeoutMs = 5000))
    }

    // ── ActionEngine ────────────────────────────────────────────────

    @Test
    fun actionEngine_tapText_successAndFailure() = runBlocking {
        val actions = ActionEngine(gateway)

        gateway.tapTextBehavior = { true }
        val ok = actions.tapText("Continue")
        assertTrue(ok.success)
        assertEquals("tap(Continue)", ok.action)
        assertEquals(listOf("Continue"), gateway.tappedTexts)

        gateway.tapTextBehavior = { false }
        val fail = actions.tapText("Missing")
        assertFalse(fail.success)
        assertEquals("Element 'Missing' not found", fail.failureReason)
        assertTrue(fail.retrySuggestion.isNotEmpty())
    }

    @Test
    fun actionEngine_tapText_exceptionBecomesFailure() = runBlocking {
        gateway.tapTextBehavior = { throw RuntimeException("boom") }
        val actions = ActionEngine(gateway)
        val result = actions.tapText("X")
        assertFalse(result.success)
        assertEquals("boom", result.failureReason)
    }

    @Test
    fun actionEngine_coordinates_longPress_andDoubleTap() = runBlocking {
        val actions = ActionEngine(gateway)

        val tap = actions.tapCoordinates(100, 200)
        assertTrue(tap.success)
        assertEquals(listOf(100 to 200), gateway.tappedCoords)

        val press = actions.longPress(10, 20)
        assertTrue(press.success)
        assertEquals(1, gateway.longPressCalls)

        val dbl = actions.doubleTap("Save")
        assertTrue(dbl.success)
        assertEquals("double_tap(Save)", dbl.action)
        assertEquals(listOf("Save", "Save"), gateway.tappedTexts)
    }

    @Test
    fun actionEngine_gestureFailures_areReported() = runBlocking {
        val actions = ActionEngine(gateway)

        gateway.tapResult = false
        val tapFail = actions.tapCoordinates(1, 2)
        assertFalse(tapFail.success)
        assertTrue(tapFail.failureReason.contains("Tap failed"))

        gateway.longPressResult = false
        assertFalse(actions.longPress(3, 4).success)

        gateway.swipeResult = false
        assertFalse(actions.swipe(0, 0, 100, 100).success)

        gateway.typeResult = false
        assertFalse(actions.clearText().success)

        gateway.navResult = false
        assertFalse(actions.goBack().success)
        assertFalse(actions.goHome().success)
        assertFalse(actions.openRecents().success)
        assertFalse(actions.openNotifications().success)
        assertFalse(actions.openQuickSettings().success)
    }

    @Test
    fun actionEngine_typeAndClear() = runBlocking {
        val actions = ActionEngine(gateway)

        gateway.typeResult = true
        val typed = actions.typeText("hello")
        assertTrue(typed.success)
        assertEquals(listOf("hello"), gateway.typedTexts)

        gateway.typeResult = false
        val failed = actions.typeText("hello")
        assertFalse(failed.success)
        assertEquals("No editable field focused", failed.failureReason)
        assertFalse(actions.clearText().success) // clear also fails while typing is broken

        gateway.typeResult = true
        val cleared = actions.clearText()
        assertTrue(cleared.success)
        assertEquals(listOf("hello", "hello", "", ""), gateway.typedTexts)
    }

    @Test
    fun actionEngine_scroll_allDirections() = runBlocking {
        val actions = ActionEngine(gateway)

        gateway.scrollResult = true
        assertTrue(actions.scrollUp().success)
        assertTrue(actions.scrollDown().success)
        assertTrue(actions.scrollLeft().success)
        assertTrue(actions.scrollRight().success)
        assertEquals(listOf("up", "down", "left", "right"), gateway.scrollDirections)

        gateway.scrollResult = false
        val fail = actions.scrollDown()
        assertFalse(fail.success)
        assertEquals("No scrollable container found", fail.failureReason)
    }

    @Test
    fun actionEngine_swipe() = runBlocking {
        val actions = ActionEngine(gateway)
        val result = actions.swipe(0, 500, 1080, 500, 200)
        assertTrue(result.success)
        assertEquals(1, gateway.swiped.size)
    }

    @Test
    fun actionEngine_navigation_ops() = runBlocking {
        val actions = ActionEngine(gateway)
        assertTrue(actions.goBack().success)
        assertTrue(actions.goHome().success)
        assertTrue(actions.openRecents().success)
        assertTrue(actions.openNotifications().success)
        assertTrue(actions.openQuickSettings().success)
        assertEquals(1, gateway.backCalls)
        assertEquals(1, gateway.homeCalls)
        assertEquals(1, gateway.recentsCalls)
        assertEquals(1, gateway.notifCalls)
        assertEquals(1, gateway.qsCalls)
    }

    @Test
    fun actionEngine_openApp_successAndFailure() = runBlocking {
        val actions = ActionEngine(gateway)

        gateway.openAppResult = true
        val ok = actions.openApp("com.spotify.music")
        assertTrue(ok.success)
        assertEquals(listOf("com.spotify.music"), gateway.openedApps)

        gateway.openAppResult = false
        val fail = actions.openApp("com.missing.app")
        assertFalse(fail.success)
        assertEquals("App not installed: com.missing.app", fail.failureReason)
    }

    @Test
    fun actionEngine_executeCommand_allBranches() = runBlocking {
        val actions = ActionEngine(gateway)
        gateway.tapTextBehavior = { true }
        gateway.typeResult = true
        gateway.scrollResult = true

        // tap
        assertTrue(actions.executeCommand("tap Continue").success)
        // double tap
        assertTrue(actions.executeCommand("double tap Save").success)
        assertEquals(2, gateway.tappedTexts.count { it == "Save" })
        // type
        assertTrue(actions.executeCommand("type hello").success)
        // scroll
        assertTrue(actions.executeCommand("scroll down").success)
        assertEquals("down", gateway.scrollDirections.last())
        // swipe (valid + invalid)
        assertTrue(actions.executeCommand("swipe 0 0 100 100").success)
        val badSwipe = actions.executeCommand("swipe 1 2")
        assertFalse(badSwipe.success)
        assertTrue(badSwipe.failureReason.contains("Expected 4 coordinates"))
        // wait
        val wait = actions.executeCommand("wait 5")
        assertTrue(wait.success)
        // open app
        assertTrue(actions.executeCommand("open app com.example.app").success)
        assertEquals("com.example.app", gateway.openedApps.last())
        // navigation aliases
        assertTrue(actions.executeCommand("back").success)
        assertTrue(actions.executeCommand("go home").success)
        assertTrue(actions.executeCommand("recents").success)
        assertTrue(actions.executeCommand("notifications").success)
        assertTrue(actions.executeCommand("quick settings").success)
        assertTrue(actions.executeCommand("clear").success)
        // unknown
        val unknown = actions.executeCommand("frobnicate the widget")
        assertFalse(unknown.success)
        assertTrue(unknown.failureReason.contains("Unknown action"))
    }

    // ── WorkflowEngine ──────────────────────────────────────────────

    @Test
    fun workflowEngine_execute_successPath() = runBlocking {
        val manager = ScreenStateManager(gateway, blackboard)
        val actions = ActionEngine(gateway)
        val workflow = WorkflowEngine(gateway, actions, manager, blackboard)
        gateway.tree = sampleTree()
        gateway.tapTextBehavior = { true }

        val steps = listOf(
            WorkflowEngine.WorkflowStep("tap Continue", "Continue"),
            WorkflowEngine.WorkflowStep("tap OK", "Accept", maxRetries = 0)
        )
        val result = workflow.execute(steps)

        assertTrue(result.success)
        assertEquals(2, result.steps.size)
        assertTrue(result.steps.all { it.actionResult.success })
        assertEquals(listOf("Continue", "OK"), gateway.tappedTexts)
        // Successes were recorded on the blackboard
        assertEquals(2, blackboard.state.value.actionHistory.size)
    }

    @Test
    fun workflowEngine_execute_failurePathRecordsToBlackboard() = runBlocking {
        val manager = ScreenStateManager(gateway, blackboard)
        val actions = ActionEngine(gateway)
        val workflow = WorkflowEngine(gateway, actions, manager, blackboard)
        gateway.tree = sampleTree()
        gateway.tapTextBehavior = { false }

        val result = workflow.execute(
            listOf(WorkflowEngine.WorkflowStep("tap Send", "Send", maxRetries = 0))
        )

        assertFalse(result.success)
        assertEquals(0, result.failedStepIndex)
        assertEquals(1, blackboard.state.value.retryCount)
        assertFalse(blackboard.state.value.lastActionResult?.success ?: true)
    }

    @Test
    fun workflowEngine_execute_usesAlternateAction() = runBlocking {
        val manager = ScreenStateManager(gateway, blackboard)
        val actions = ActionEngine(gateway)
        val workflow = WorkflowEngine(gateway, actions, manager, blackboard)
        gateway.tree = sampleTree()
        gateway.tapTextBehavior = { it == "To" } // primary fails, alternate works

        val result = workflow.execute(
            listOf(WorkflowEngine.WorkflowStep(
                action = "tap Compose",
                description = "Compose",
                maxRetries = 0,
                alternateActions = listOf("tap To")
            ))
        )

        assertTrue(result.success)
        assertTrue(result.steps[0].alternateUsed)
        assertEquals(1, result.steps[0].attempts)
        assertTrue(gateway.tappedTexts.contains("To"))
    }

    @Test
    fun workflowEngine_execute_verifyFailureStillSucceeds() = runBlocking {
        val manager = ScreenStateManager(gateway, blackboard)
        val actions = ActionEngine(gateway)
        val workflow = WorkflowEngine(gateway, actions, manager, blackboard)
        gateway.tree = sampleTree()
        gateway.tapTextBehavior = { it == "Continue" } // verify fails, action passes

        val result = workflow.execute(
            listOf(WorkflowEngine.WorkflowStep(
                action = "tap Continue",
                description = "Continue",
                maxRetries = 0,
                verifyAction = "tap Verify"
            ))
        )

        assertTrue(result.success)
        assertTrue(result.steps[0].actionResult.success)
    }

    @Test
    fun workflowEngine_preBuiltWorkflows() {
        val manager = ScreenStateManager(gateway, blackboard)
        val actions = ActionEngine(gateway)
        val workflow = WorkflowEngine(gateway, actions, manager, blackboard)

        val sms = workflow.sendSmsWorkflow("Mom", "Hi")
        assertEquals(5, sms.size)
        assertEquals("tap Compose", sms[0].action)
        assertTrue(sms[1].alternateActions.isNotEmpty())

        val search = workflow.searchInAppWorkflow("gmail", "inbox")
        assertEquals(4, search.size)
        assertEquals("open app gmail", search[0].action)

        val form = workflow.fillFormWorkflow(listOf("Name" to "Alice", "Email" to "a@b.c"))
        assertEquals(4, form.size)
        assertEquals("tap Name", form[0].action)
        assertEquals("type Alice", form[1].action)
    }

    // ── AccessibilityIntelligence ───────────────────────────────────

    @Test
    fun intelligence_executeNaturalLanguage_fullDispatch() = runBlocking {
        gateway.tree = sampleTree()
        gateway.tapTextBehavior = { true }
        gateway.typeResult = true
        gateway.scrollResult = true
        val intelligence = AccessibilityIntelligence(gateway, blackboard)

        // regex role branch: "tap the button continue"
        assertTrue(intelligence.executeNaturalLanguage("tap the button continue").success)
        assertEquals("continue", gateway.tappedTexts.last())

        // simple tap branch
        assertTrue(intelligence.executeNaturalLanguage("tap the save button").success)
        assertEquals("the save button", gateway.tappedTexts.last())

        // type
        assertTrue(intelligence.executeNaturalLanguage("type hello").success)
        // type without text → explicit failure
        val noType = intelligence.executeNaturalLanguage("type")
        assertFalse(noType.success)
        assertTrue(noType.failureReason.contains("What should I type"))

        // scroll variants
        assertTrue(intelligence.executeNaturalLanguage("scroll up").success)
        assertTrue(intelligence.executeNaturalLanguage("scroll down").success)
        assertTrue(intelligence.executeNaturalLanguage("scroll left").success)
        assertTrue(intelligence.executeNaturalLanguage("scroll right").success)
        assertTrue(intelligence.executeNaturalLanguage("scroll").success)
        assertTrue(intelligence.executeNaturalLanguage("scroll sideways").success)

        // navigation
        assertTrue(intelligence.executeNaturalLanguage("go back").success)
        assertTrue(intelligence.executeNaturalLanguage("home").success)
        assertTrue(intelligence.executeNaturalLanguage("recent apps").success)
        assertTrue(intelligence.executeNaturalLanguage("notifications").success)

        // open app
        assertTrue(intelligence.executeNaturalLanguage("open app com.example.app").success)
        assertEquals("com.example.app", gateway.openedApps.last())

        // swipe
        assertTrue(intelligence.executeNaturalLanguage("swipe 0 0 100 100").success)
        val badSwipe = intelligence.executeNaturalLanguage("swipe 1 2")
        assertFalse(badSwipe.success)

        // clear
        assertTrue(intelligence.executeNaturalLanguage("clear").success)
    }

    @Test
    fun intelligence_describeScreen_populatesSummary() = runBlocking {
        gateway.tree = sampleTree()
        val intelligence = AccessibilityIntelligence(gateway, blackboard)

        intelligence.refreshScreen()
        val summary = intelligence.describeScreen()

        assertTrue(summary.contains("App: com.android.settings"))
        assertTrue(summary.contains("Interactive elements"))
        assertTrue(summary.contains("Continue"))
        assertTrue(summary.contains("Active dialogs"))
    }

    @Test
    fun intelligence_describeScreen_emptyTree_returnsHint() = runBlocking {
        gateway.tree = null
        val intelligence = AccessibilityIntelligence(gateway, blackboard)

        val summary = intelligence.describeScreen()

        assertTrue(summary.contains("No screen information available"))
    }

    @Test
    fun intelligence_isReady_falseWhenServiceNotRunning() {
        val intelligence = AccessibilityIntelligence(gateway, blackboard)
        assertFalse(intelligence.isReady())
    }

    @Test
    fun intelligence_refreshScreen_updatesCurrentScreen() = runBlocking {
        gateway.tree = sampleTree()
        val intelligence = AccessibilityIntelligence(gateway, blackboard)

        assertNull(intelligence.screenManager.getCurrentScreen())
        val screen = intelligence.refreshScreen()
        assertNotNull(intelligence.screenManager.getCurrentScreen())
        assertEquals(screen.packageName, intelligence.screenManager.getCurrentScreen()?.packageName)
    }
}
