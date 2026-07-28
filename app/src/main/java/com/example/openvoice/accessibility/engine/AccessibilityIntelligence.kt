package com.example.openvoice.accessibility.engine

import com.example.openvoice.accessibility.VoiceAccessibilityService
import com.example.openvoice.accessibility.SemanticUiNode
import com.example.openvoice.accessibility.ScreenState
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AccessibilityIntelligence — The central coordinator for all accessibility features.
 *
 * Ties together:
 * - ScreenStateManager (capture + navigation history)
 * - UiSearchEngine (find elements by text, role, position, etc.)
 * - ActionEngine (execute tap, type, scroll, swipe, etc.)
 * - WorkflowEngine (multi-step automation)
 *
 * Usage:
 *   val a11y = AccessibilityIntelligence(service, blackboard)
 *   a11y.start()
 *   val btn = a11y.search.findButton(screen, "Continue")
 *   a11y.actions.tapNode(btn)
 *   a11y.workflow.execute(workflowSteps)
 */
class AccessibilityIntelligence(
    private val a11y: VoiceAccessibilityService,
    private val blackboard: TaskBlackboard
) {
    val screenManager: ScreenStateManager
    val search: UiSearchEngine
    val actions: ActionEngine
    val workflow: WorkflowEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        screenManager = ScreenStateManager(a11y, blackboard)
        search = UiSearchEngine()
        actions = ActionEngine(a11y)
        workflow = WorkflowEngine(a11y, actions, screenManager, blackboard)
        Logger.i("AccessibilityIntelligence initialized", "A11y")
    }

    /** Start monitoring the screen state automatically. */
    fun start() {
        scope.launch {
            screenManager.trackNavigation()
            Logger.i("Screen monitoring started", "A11y")
        }
    }

    /** Refresh the current screen state. */
    suspend fun refreshScreen(): ScreenState {
        val screen = screenManager.captureScreen()
        screenManager.trackNavigation()
        return screen
    }

    /**
     * Execute a natural language accessibility command.
     * e.g., "tap the Continue button", "scroll down", "type Hello"
     */
    suspend fun executeNaturalLanguage(command: String): ActionResult {
        val lower = command.lowercase().trim()

        return when {
            // Tap with role
            lower.matches(Regex("tap (?:the |a |an )?(\\w+ )?(button|link|text|icon|menu|item|image) \"?(.+?)\"?$")) -> {
                val label = Regex("tap (?:the |a |an )?(?:\\w+ )?(?:button|link|text|icon|menu|item|image) \"?(.+?)\"?$")
                    .find(lower)?.groupValues?.get(1) ?: ""
                actions.tapText(label)
            }
            // Simple tap
            lower.startsWith("tap ") -> actions.tapText(lower.removePrefix("tap ").trim())
            // Type
            lower.startsWith("type ") -> actions.typeText(lower.removePrefix("type ").trim())
            lower == "type" || lower == "type text" -> ActionResult.fail("type", "What should I type?")
            // Scroll
            lower.startsWith("scroll ") -> actions.scroll(lower.removePrefix("scroll ").trim())
            lower == "scroll down" || lower == "scroll" -> actions.scrollDown()
            lower == "scroll up" -> actions.scrollUp()
            lower == "scroll left" -> actions.scrollLeft()
            lower == "scroll right" -> actions.scrollRight()
            // Navigation
            lower == "go back" || lower == "back" -> actions.goBack()
            lower == "go home" || lower == "home" -> actions.goHome()
            lower == "recents" || lower == "recent apps" -> actions.openRecents()
            lower == "notifications" -> actions.openNotifications()
            lower.startsWith("open app ") -> actions.openApp(lower.removePrefix("open app ").trim())
            // Swipe
            lower.startsWith("swipe ") -> {
                val parts = lower.removePrefix("swipe ").trim().split(Regex("\\s+"))
                if (parts.size >= 4) actions.swipe(
                    parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0,
                    parts[2].toIntOrNull() ?: 0, parts[3].toIntOrNull() ?: 0)
                else ActionResult.fail("swipe", "Expected 4 coordinates, got ${parts.size}")
            }
            // Clear
            lower == "clear" || lower == "clear text" -> actions.clearText()
            // Unknown
            else -> actions.executeCommand(command)
        }
    }

    /**
     * Get a readable summary of the current screen.
     */
    suspend fun describeScreen(): String {
        val screen = screenManager.getCurrentScreen() ?: screenManager.captureScreen()
        if (screen.isEmpty) return "No screen information available. Accessibility service may not be enabled."

        val sb = StringBuilder()
        sb.appendLine("App: ${screen.packageName}")
        sb.appendLine()

        val interactive = screen.interactiveElements
        if (interactive.isEmpty()) {
            sb.appendLine("No interactive elements found.")
            return sb.toString()
        }

        sb.appendLine("Interactive elements (${interactive.size}):")
        for ((i, el) in interactive.withIndex()) {
            val label = el.label
            val bounds = el.boundsString
            sb.appendLine("  ${i + 1}. ${el.role} \"$label\" at $bounds" +
                if (el.isClickable) " [clickable]" else "" +
                if (el.isEditable) " [editable]" else "" +
                if (el.isChecked == true) " [checked]" else "")
        }

        if (screen.dialogs.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Active dialogs:")
            for (d in screen.dialogs) {
                sb.appendLine("  - ${d.label}")
            }
        }

        return sb.toString()
    }

    /** Verify accessibility service is connected and working. */
    fun isReady(): Boolean = VoiceAccessibilityService.isRunning() && a11y.getUiTree() != null
}
