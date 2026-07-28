package com.example.openvoice.accessibility.engine

import com.example.openvoice.accessibility.VoiceAccessibilityService
import com.example.openvoice.accessibility.SemanticUiNode
import com.example.openvoice.accessibility.ScreenState
import com.example.openvoice.accessibility.UiRole
import com.example.openvoice.task.TaskBlackboard
import com.example.openvoice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * ScreenStateManager — Maintains an internal representation of the current screen.
 *
 * Responsibilities:
 * - Parse the accessibility node tree into semantic nodes
 * - Track navigation history (stack of apps/activities visited)
 * - Detect dialogs, keyboard state
 * - Maintain the last successful interaction point
 * - Provide screen diffing (detect what changed)
 */
class ScreenStateManager(
    private val a11y: VoiceAccessibilityService,
    private val blackboard: TaskBlackboard
) {

    private val navigationStack = mutableListOf<NavigationEntry>()
    private var lastScreenState: ScreenState? = null
    private var lastCaptureTime = 0L

    data class NavigationEntry(
        val app: String,
        val activity: String,
        val screenName: String,
        val timestamp: Long
    )

    // ── Screen Capture ───────────────────────────────────────────────

    /**
     * Capture the current screen state from the accessibility service.
     * Updates the TaskBlackboard automatically.
     */
    suspend fun captureScreen(): ScreenState = withContext(Dispatchers.Main) {
        val start = System.currentTimeMillis()
        val uiTree = a11y.getUiTree()
        val screen = ScreenState.fromUiTree(uiTree)
        val app = a11y.getActivePackage() ?: ""
        val activity = app

        screen.copy(
            packageName = app,
            activityName = activity,
            capturedAt = System.currentTimeMillis()
        ).also { state ->
            lastScreenState = state
            lastCaptureTime = state.capturedAt

            // Update blackboard
            blackboard.setUiTree(uiTree)
            if (app.isNotEmpty()) {
                blackboard.setCurrentApp(app)
                blackboard.setCurrentActivity(activity)
            }

            // Detect dialogs
            if (state.hasDialogs) {
                blackboard.setActiveDialogs(state.dialogs.map { it.label })
            }

            val ms = System.currentTimeMillis() - start
            Logger.d("Screen captured: ${state.packageName} (${ms}ms), " +
                "${state.interactiveElements.size} interactive elements", "ScreenState")
        }
    }

    /** Get the latest captured screen state. */
    fun getCurrentScreen(): ScreenState? = lastScreenState

    /** Get the semantic tree from the latest capture. */
    fun getTree(): SemanticUiNode? = lastScreenState?.semanticTree

    // ── Navigation Tracking ──────────────────────────────────────────

    /**
     * Track navigation to a new app/activity.
     * Maintains a stack for back-navigation awareness.
     */
    suspend fun trackNavigation() = withContext(Dispatchers.Main) {
        val app = a11y.getActivePackage() ?: return@withContext
        val screen = captureScreen()
        val entry = NavigationEntry(app, screen.activityName, screen.packageName, System.currentTimeMillis())

        // Only push if changed
        if (navigationStack.lastOrNull()?.app != app ||
            navigationStack.lastOrNull()?.activity != screen.activityName) {
            navigationStack.add(entry)
            if (navigationStack.size > 20) navigationStack.removeAt(0)
        }

        blackboard.setCurrentApp(app)
        blackboard.setCurrentActivity(screen.activityName)
        Logger.d("Navigated to: $app / ${screen.activityName}", "ScreenState")
    }

    fun getNavigationStack(): List<NavigationEntry> = navigationStack.toList()

    fun getCurrentApp(): String = navigationStack.lastOrNull()?.app ?: ""
    fun getPreviousApp(): String = navigationStack.getOrNull(navigationStack.size - 2)?.app ?: ""

    // ── State Queries ────────────────────────────────────────────────

    /** Check if the screen has changed since last capture. */
    suspend fun hasScreenChanged(): Boolean {
        val current = captureScreen()
        val last = lastScreenState
        if (last == null) return true
        return current.packageName != last.packageName ||
            current.interactiveElements.size != last.interactiveElements.size
    }

    /** Wait for the screen to stabilize (no content change for a short period). */
    suspend fun waitForStableScreen(timeoutMs: Long = 5000): Boolean {
        val start = System.currentTimeMillis()
        var lastContent = ""
        var stableCount = 0

        while (System.currentTimeMillis() - start < timeoutMs) {
            val screen = captureScreen()
            val currentContent = screen.semanticTree?.let { treeToString(it) } ?: ""

            if (currentContent == lastContent && currentContent.isNotEmpty()) {
                stableCount++
                if (stableCount >= 3) return true // 3 consecutive identical captures
            } else {
                stableCount = 0
                lastContent = currentContent
            }
            delay(200)
        }
        return false
    }

    /** Detect active dialogs on screen. */
    suspend fun detectDialogs(): List<SemanticUiNode> {
        val screen = getCurrentScreen() ?: captureScreen()
        return screen.dialogs
    }

    /** Check if keyboard is likely visible. */
    fun isKeyboardVisible(): Boolean = lastScreenState?.keyboardVisible ?: false

    // ── History ──────────────────────────────────────────────────────

    /** Get screen history for navigation recovery. */
    fun canGoBack(): Boolean = navigationStack.size > 1

    fun clearHistory() { navigationStack.clear() }

    private fun treeToString(node: SemanticUiNode): String = buildString {
        append("${node.role}:${node.text ?: ""}:${node.boundsString};")
        node.children.forEach { append(treeToString(it)) }
    }
}
