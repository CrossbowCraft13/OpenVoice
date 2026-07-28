package com.example.openvoice.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.openvoice.util.Logger
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-grade Android Accessibility Automation Service.
 *
 * Capabilities:
 * - UI hierarchy parsing into semantic trees
 * - Multi-strategy node search (text, description, class, bounds, role)
 * - Gesture injection: tap, longPress, swipe, scroll, type
 * - Global actions: back, home, recents, notifications, quick settings
 * - Application navigation (open app, switch apps)
 * - Failure detection and automatic retry
 * - Window change tracking
 *
 * Usage:
 *   The assistant calls executeActions() with a list of commands.
 *   Each command is a single action like "tap Submit" or "scroll down".
 */
class VoiceAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: VoiceAccessibilityService? = null
        fun getInstance() = instance
        fun isRunning() = instance != null
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineName("a11y"))
    private var lastWindowChange = 0L
    private val gestureComplete = AtomicBoolean(false)

    override fun onServiceConnected() {
        instance = this
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            notificationTimeout = 50
        }
        serviceInfo = info
        Logger.i("AccessibilityService connected", "A11y")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastWindowChange = System.currentTimeMillis()
                Logger.d("Window changed: ${event.packageName} / ${event.className}", "A11y")
            }
        }
    }

    override fun onInterrupt() { Logger.d("AccessibilityService interrupted", "A11y") }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    // ── Core: UI Hierarchy Access ──────────────────────────────────────────────

    /** Get the root node of the currently active window. */
    private fun activeRoot(): AccessibilityNodeInfo? {
        // Prefer the active window to avoid including system UI
        val windows = windows
        if (windows != null) {
            for (w in windows) {
                if (w.isActive && w.root != null) return w.root
            }
        }
        return rootInActiveWindow
    }

    // ── Node Search (strategy: BFS with multiple matchers) ─────────────────────

    data class SearchCriteria(
        val text: String? = null,
        val textContains: String? = null,
        val description: String? = null,
        val descriptionContains: String? = null,
        val className: String? = null,
        val viewId: String? = null,
        val isClickable: Boolean? = null,
        val isEditable: Boolean? = null,
        val isChecked: Boolean? = null,
        val isCheckable: Boolean? = null,
        val isScrollable: Boolean? = null,
        val isFocusable: Boolean? = null,
        val isEnabled: Boolean? = true,
        val maxDepth: Int = Int.MAX_VALUE
    )

    /**
     * Find the first node matching the given criteria.
     * Uses BFS for optimal performance (finds shallowest match first).
     */
    fun findNode(criteria: SearchCriteria): AccessibilityNodeInfo? {
        val root = activeRoot() ?: return null
        try {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty()) {
                val (node, depth) = queue.removeFirst()
                if (depth > criteria.maxDepth) continue
                if (matches(node, criteria)) {
                    return node // Caller must recycle
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        // Create a new parent reference for each child to avoid
                        // recycling conflicts
                        queue.add(child to depth + 1)
                    }
                }
            }
            return null
        } catch (e: Exception) {
            Logger.e("findNode error: ${e.message}", "A11y")
            return null
        }
    }

    /** Find ALL nodes matching criteria (for forms, lists, etc.). */
    fun findNodes(criteria: SearchCriteria, max: Int = 50): List<AccessibilityNodeInfo> {
        val root = activeRoot() ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        try {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty() && results.size < max) {
                val (node, depth) = queue.removeFirst()
                if (depth > criteria.maxDepth) continue
                if (matches(node, criteria)) {
                    results.add(node)
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        } catch (e: Exception) {
            Logger.e("findNodes error: ${e.message}", "A11y")
        }
        return results
    }

    private fun matches(node: AccessibilityNodeInfo, c: SearchCriteria): Boolean {
        if (c.text != null && node.text?.toString() != c.text) return false
        if (c.textContains != null && !(node.text?.toString()?.lowercase()?.contains(c.textContains.lowercase()) ?: false)) return false
        if (c.description != null && node.contentDescription?.toString() != c.description) return false
        if (c.descriptionContains != null && !(node.contentDescription?.toString()?.lowercase()?.contains(c.descriptionContains.lowercase()) ?: false)) return false
        if (c.className != null) {
            val cn = node.className?.toString()?.substringAfterLast('.') ?: ""
            if (!cn.equals(c.className, true) && node.className?.toString() != c.className) return false
        }
        if (c.viewId != null && node.viewIdResourceName != c.viewId) return false
        if (c.isClickable != null && node.isClickable != c.isClickable) return false
        if (c.isEditable != null && node.isEditable != c.isEditable) return false
        if (c.isChecked != null && node.isChecked != c.isChecked) return false
        if (c.isCheckable != null && node.isCheckable != c.isCheckable) return false
        if (c.isScrollable != null && node.isScrollable != c.isScrollable) return false
        if (c.isFocusable != null && node.isFocusable != c.isFocusable) return false
        if (c.isEnabled != null && node.isEnabled != c.isEnabled) return false
        return node.isVisibleToUser
    }

    /** Convenience: find first node containing text. */
    fun findNodeByText(text: String) = findNode(SearchCriteria(textContains = text))

    /** Convenience: find clickable node with text. */
    fun findClickableByText(text: String) = findNode(SearchCriteria(textContains = text, isClickable = true))

    /** Convenience: find editable text field. */
    fun findTextField() = findNode(SearchCriteria(isEditable = true))

    /** Convenience: find scrollable container. */
    fun findScrollable() = findNode(SearchCriteria(isScrollable = true))

    // ── Semantic UI Tree ───────────────────────────────────────────────────────

    /** Parsed, lightweight node for analysis without Android resource ownership. */
    data class UiNode(
        val text: String?,
        val description: String?,
        val className: String?,
        val packageName: String?,
        val viewId: String?,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isChecked: Boolean,
        val isCheckable: Boolean,
        val isFocusable: Boolean,
        val isEnabled: Boolean,
        val isPassword: Boolean,
        val isVisible: Boolean,
        val depth: Int,
        val children: List<UiNode>
    ) {
        val centerX: Int get() = (bounds.left + bounds.right) / 2
        val centerY: Int get() = (bounds.top + bounds.bottom) / 2
        val role: String get() = className?.substringAfterLast('.') ?: "unknown"
        val label: String get() = text ?: description ?: viewId ?: role
    }

    /** Parse the current screen into a semantic UiNode tree. */
    fun getUiTree(): UiNode? {
        val root = activeRoot() ?: return null
        return try { parseNode(root, 0) } finally { root.recycle() }
    }

    private fun parseNode(node: AccessibilityNodeInfo, depth: Int): UiNode {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val children = mutableListOf<UiNode>()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                children.add(parseNode(child, depth + 1))
                child.recycle()
            }
        }
        return UiNode(
            text = node.text?.toString(),
            description = node.contentDescription?.toString(),
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            viewId = node.viewIdResourceName,
            bounds = bounds,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isChecked = node.isChecked,
            isCheckable = node.isCheckable,
            isFocusable = node.isFocusable,
            isEnabled = node.isEnabled,
            isPassword = node.isPassword,
            isVisible = node.isVisibleToUser,
            depth = depth,
            children = children
        )
    }

    /** Get flat list of all interactive elements on screen. */
    fun getInteractiveElements(): List<UiNode> {
        val collect = mutableListOf<UiNode>()
        fun walk(n: UiNode) {
            if (n.isClickable || n.isEditable || n.isScrollable) {
                if (n.isVisible) collect.add(n)
            }
            n.children.forEach { walk(it) }
        }
        getUiTree()?.let { walk(it) }
        return collect
    }

    // ── Gesture Injection ──────────────────────────────────────────────────────

    private fun buildGesture(path: Path, durationMs: Long, startOffset: Long = 0): GestureDescription {
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, startOffset, durationMs))
            .build()
    }

    /** Tap at screen coordinates. Returns after gesture completes or timeout. */
    suspend fun tap(x: Int, y: Int): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val callback = object : GestureDescription.GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) { cont.resume(true) { } }
            override fun onCancelled(gesture: GestureDescription?) { cont.resume(false) { } }
        }
        dispatchGesture(buildGesture(path, 10), callback, null)
        cont.invokeOnCancellation { cont.resume(false) { } }
    }

    /** Tap the center of a node. */
    suspend fun tapNode(node: UiNode): Boolean {
        return tap(node.centerX, node.centerY)
    }

    /** Find node by text and tap it. */
    suspend fun tapText(text: String): Boolean {
        // Try clickable first, then any visible
        val node = findClickableByText(text) ?: findNodeByText(text) ?: return false
        return try {
            val r = Rect().also { node.getBoundsInScreen(it) }
            tap(r.centerX(), r.centerY())
        } finally { node.recycle() }
    }

    /** Long press at coordinates. */
    suspend fun longPress(x: Int, y: Int): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val callback = object : GestureDescription.GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) { cont.resume(true) { } }
            override fun onCancelled(gesture: GestureDescription?) { cont.resume(false) { } }
        }
        dispatchGesture(buildGesture(path, 500), callback, null)
    }

    /** Swipe from (x1,y1) to (x2,y2). */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val callback = object : GestureDescription.GestureResultCallback() {
                override fun onCompleted(gesture: GestureDescription?) { cont.resume(true) { } }
                override fun onCancelled(gesture: GestureDescription?) { cont.resume(false) { } }
            }
            dispatchGesture(buildGesture(path, durationMs), callback, null)
        }

    /** Scroll in a direction. Returns true if gesture was dispatched. */
    suspend fun scroll(direction: String): Boolean {
        val scrollable = findScrollable() ?: return false
        return try {
            val r = Rect().also { scrollable.getBoundsInScreen(it) }
            val cx = r.centerX()
            val cy = r.centerY()
            val offset = (r.height() * 0.4).toInt().coerceAtLeast(100)
            val (x1, y1, x2, y2) = when (direction.lowercase()) {
                "down" -> listOf(cx, cy - offset, cx, cy + offset)
                "up" -> listOf(cx, cy + offset, cx, cy - offset)
                "left" -> listOf(cx + offset, cy, cx - offset, cy)
                "right" -> listOf(cx - offset, cy, cx + offset, cy)
                else -> return false
            }
            swipe(x1, y1, x2, y2)
        } finally { scrollable.recycle() }
    }

    /** Type text into the currently focused editable field. */
    suspend fun type(text: String): Boolean {
        // Try finding the focused editable node
        val focused = try {
            activeRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) { null }

        if (focused != null && focused.isEditable) {
            return try {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARG_SET_TEXT_CHARSEQUENCE, text)
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } finally { focused.recycle() }
        }

        // Fallback: paste from clipboard
        return try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("openvoice", text))
            performGlobalAction(GLOBAL_ACTION_PASTE)
            true
        } catch (e: Exception) { false }
    }

    // ── Global Actions ─────────────────────────────────────────────────────────

    suspend fun goBack(): Boolean {
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(150)
        return true
    }

    suspend fun goHome(): Boolean {
        performGlobalAction(GLOBAL_ACTION_HOME)
        delay(150)
        return true
    }

    suspend fun openRecents(): Boolean {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        delay(150)
        return true
    }

    suspend fun openNotifications(): Boolean {
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        delay(300)
        return true
    }

    suspend fun openQuickSettings(): Boolean {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        delay(300)
        return true
    }

    // ── Application Navigation ─────────────────────────────────────────────────

    /** Open an app by package name using the system launcher. */
    suspend fun openApp(packageName: String): Boolean {
        return try {
            val intent = packageManager?.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                delay(500) // Wait for app to open
                true
            } else false
        } catch (e: Exception) { false }
    }

    /** Switch to the most recent app. */
    suspend fun switchToPreviousApp(): Boolean {
        openRecents()
        delay(200)
        // Tap on the first card (most recent)
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        delay(200)
        return true
    }

    // ── Action Planner ─────────────────────────────────────────────────────────

    /**
     * Execute a sequence of action commands.
     * Each command can be:
     *   "tap <text>"           → Find and tap element
     *   "type <text>"          → Type into focused field
     *   "scroll <dir>"         → Scroll up/down/left/right
     *   "swipe <x1> <y1> <x2> <y2>" → Swipe gesture
     *   "back"                 → Navigate back
     *   "home"                 → Go to home screen
     *   "wait <ms>"            → Pause execution
     *   "open app <package>"   → Launch an app
     *   "tap coord <x> <y>"    → Tap at raw coordinates
     *
     * Returns Pair(success, failedActionIndex).
     */
    suspend fun executeActions(actions: List<String>): Pair<Boolean, Int> {
        for ((index, action) in actions.withIndex()) {
            delay(250) // Allow UI to settle between actions
            val start = System.nanoTime()
            val success = executeSingleAction(action)
            val ms = (System.nanoTime() - start) / 1_000_000
            Logger.d("Action[$index]: $action → ${if (success) "OK" else "FAIL"} (${ms}ms)", "A11y")
            if (!success) {
                // Retry once after longer delay
                delay(500)
                val retryOk = executeSingleAction(action)
                if (!retryOk) return Pair(false, index)
            }
        }
        return Pair(true, -1)
    }

    /** Execute a single action command. Thread-safe. */
    suspend fun executeSingleAction(cmd: String): Boolean {
        val lower = cmd.trim()
        return when {
            lower.startsWith("tap ") -> {
                val rest = lower.removePrefix("tap ").trim()
                if (rest.startsWith("coord")) {
                    val parts = rest.removePrefix("coord").trim().split(Regex("\\s+"))
                    if (parts.size >= 2) tap(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0)
                    else false
                } else tapText(rest)
            }
            lower.startsWith("type ") -> type(lower.removePrefix("type ").trim())
            lower.startsWith("scroll ") -> scroll(lower.removePrefix("scroll ").trim())
            lower.startsWith("swipe ") -> {
                val parts = lower.removePrefix("swipe ").trim().split(Regex("\\s+"))
                if (parts.size >= 4) swipe(
                    parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0,
                    parts[2].toIntOrNull() ?: 0, parts[3].toIntOrNull() ?: 0
                ) else false
            }
            lower.startsWith("wait ") -> {
                val ms = lower.removePrefix("wait ").trim().toLongOrNull() ?: 1000
                delay(ms); true
            }
            lower.startsWith("open app ") -> openApp(lower.removePrefix("open app ").trim())
            lower == "back" -> goBack()
            lower == "home" -> goHome()
            lower == "recents" -> openRecents()
            lower == "notifications" -> openNotifications()
            lower == "quick settings" -> openQuickSettings()
            else -> {
                Logger.w("Unknown action: $cmd", "A11y")
                false
            }
        }
    }

    // ── Window Info ────────────────────────────────────────────────────────────

    /** Get the current active package name, if available. */
    fun getActivePackage(): String? {
        return try { activeRoot()?.packageName?.toString() }
        catch (_: Exception) { null }
    }

    /** Get display dimensions. */
    fun getScreenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    /** Time since last window change (for debouncing). */
    fun msSinceLastWindowChange(): Long = System.currentTimeMillis() - lastWindowChange
}
