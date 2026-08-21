package io.github.crossbowcraft13.openvoice.accessibility.engine

import io.github.crossbowcraft13.openvoice.accessibility.AccessibilityGateway
import io.github.crossbowcraft13.openvoice.accessibility.SemanticUiNode
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.delay

/**
 * Structured result from any accessibility action.
 */
data class ActionResult(
    val action: String,
    val success: Boolean,
    val failureReason: String = "",
    val retrySuggestion: String = "",
    val executionTimeMs: Long = 0,
    val details: Map<String, String> = emptyMap()
) {
    companion object {
        fun ok(action: String, timeMs: Long) = ActionResult(action, true, executionTimeMs = timeMs)
        fun fail(action: String, reason: String, retry: String = "", timeMs: Long = 0) =
            ActionResult(action, false, reason, retry, timeMs)
    }
}

/**
 * ActionEngine — Generic, reusable accessibility actions.
 *
 * Every action:
 * - Returns a structured ActionResult (success/failure + reason + retry suggestion)
 * - Measures execution time
 * - Logs the action
 * - Goes through the VoiceAccessibilityService
 */
class ActionEngine(private val a11y: AccessibilityGateway) {

    // ── Core Gestures ───────────────────────────────────────────────

    /** Tap an element identified by text. Returns ActionResult. */
    suspend fun tapText(text: String): ActionResult {
        val start = System.currentTimeMillis()
        return try {
            val found = a11y.tapText(text)
            val ms = System.currentTimeMillis() - start
            if (found) ActionResult.ok("tap($text)", ms)
            else ActionResult.fail("tap($text)", "Element '$text' not found",
                "Try scrolling first or searching with partial text", ms)
        } catch (e: Exception) {
            ActionResult.fail("tap($text)", e.message ?: "Unknown", timeMs = System.currentTimeMillis() - start)
        }
    }

    /** Tap at exact screen coordinates. */
    suspend fun tapCoordinates(x: Int, y: Int): ActionResult {
        val start = System.currentTimeMillis()
        return try {
            val success = a11y.tap(x, y)
            val ms = System.currentTimeMillis() - start
            if (success) ActionResult.ok("tap($x,$y)", ms)
            else ActionResult.fail("tap($x,$y)", "Tap failed at ($x,$y)", timeMs = ms)
        } catch (e: Exception) {
            ActionResult.fail("tap($x,$y)", e.message ?: "Unknown")
        }
    }

    /** Double-tap an element. */
    suspend fun doubleTap(text: String): ActionResult {
        val start = System.currentTimeMillis()
        tapText(text)
        delay(50)
        val result = tapText(text)
        return result.copy(action = "double_tap($text)",
            executionTimeMs = System.currentTimeMillis() - start)
    }

    /** Long press at coordinates. */
    suspend fun longPress(x: Int, y: Int): ActionResult {
        val start = System.currentTimeMillis()
        return try {
            val success = a11y.longPress(x, y)
            val ms = System.currentTimeMillis() - start
            if (success) ActionResult.ok("long_press($x,$y)", ms)
            else ActionResult.fail("long_press($x,$y)", "Long press failed at ($x,$y)", timeMs = ms)
        } catch (e: Exception) {
            ActionResult.fail("long_press($x,$y)", e.message ?: "Unknown")
        }
    }

    // ── Text Input ──────────────────────────────────────────────────

    /** Type text into the currently focused field. */
    suspend fun typeText(text: String): ActionResult {
        val start = System.currentTimeMillis()
        return try {
            val success = a11y.type(text)
            val ms = System.currentTimeMillis() - start
            if (success) ActionResult.ok("type($text)", ms)
            else ActionResult.fail("type($text)", "No editable field focused",
                "Tap on a text field first", ms)
        } catch (e: Exception) {
            ActionResult.fail("type($text)", e.message ?: "Unknown")
        }
    }

    /** Clear text from the focused field. */
    suspend fun clearText(): ActionResult {
        val start = System.currentTimeMillis()
        return try {
            val success = a11y.type("")
            val ms = System.currentTimeMillis() - start
            if (success) ActionResult.ok("clear_text", ms)
            else ActionResult.fail("clear_text", "No editable field focused",
                "Tap on a text field first", ms)
        } catch (e: Exception) {
            ActionResult.fail("clear_text", e.message ?: "Unknown")
        }
    }

    // ── Scrolling ───────────────────────────────────────────────────

    suspend fun scroll(direction: String): ActionResult {
        val start = System.currentTimeMillis()
        return try {
            val success = a11y.scroll(direction)
            val ms = System.currentTimeMillis() - start
            if (success) ActionResult.ok("scroll($direction)", ms)
            else ActionResult.fail("scroll($direction)", "No scrollable container found",
                "Try scrolling the other direction", ms)
        } catch (e: Exception) {
            ActionResult.fail("scroll($direction)", e.message ?: "Unknown")
        }
    }

    suspend fun scrollUp() = scroll("up")
    suspend fun scrollDown() = scroll("down")
    suspend fun scrollLeft() = scroll("left")
    suspend fun scrollRight() = scroll("right")

    // ── Swipe ───────────────────────────────────────────────────────

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): ActionResult {
        val start = System.currentTimeMillis()
        return try {
            val success = a11y.swipe(x1, y1, x2, y2, durationMs)
            val ms = System.currentTimeMillis() - start
            if (success) ActionResult.ok("swipe($x1,$y1→$x2,$y2)", ms)
            else ActionResult.fail("swipe", "Swipe failed", timeMs = ms)
        } catch (e: Exception) {
            ActionResult.fail("swipe", e.message ?: "Unknown")
        }
    }

    // ── Navigation ──────────────────────────────────────────────────

    private suspend fun navResult(action: String, executed: Boolean): ActionResult {
        val ms = System.currentTimeMillis()
        return if (executed) ActionResult.ok(action, ms)
        else ActionResult.fail(action, "$action gesture failed", timeMs = ms)
    }

    suspend fun goBack(): ActionResult {
        val start = System.currentTimeMillis()
        val executed = a11y.goBack()
        return navResult("back", executed).copy(executionTimeMs = System.currentTimeMillis() - start)
    }

    suspend fun goHome(): ActionResult {
        val start = System.currentTimeMillis()
        val executed = a11y.goHome()
        return navResult("home", executed).copy(executionTimeMs = System.currentTimeMillis() - start)
    }

    suspend fun openRecents(): ActionResult {
        val start = System.currentTimeMillis()
        val executed = a11y.openRecents()
        return navResult("recents", executed).copy(executionTimeMs = System.currentTimeMillis() - start)
    }

    suspend fun openNotifications(): ActionResult {
        val start = System.currentTimeMillis()
        val executed = a11y.openNotifications()
        return navResult("notifications", executed).copy(executionTimeMs = System.currentTimeMillis() - start)
    }

    suspend fun openQuickSettings(): ActionResult {
        val start = System.currentTimeMillis()
        val executed = a11y.openQuickSettings()
        return navResult("quick_settings", executed).copy(executionTimeMs = System.currentTimeMillis() - start)
    }

    // ── App Launch ──────────────────────────────────────────────────

    suspend fun openApp(packageName: String): ActionResult {
        val start = System.currentTimeMillis()
        return try {
            val success = a11y.openApp(packageName)
            val ms = System.currentTimeMillis() - start
            if (success) ActionResult.ok("open_app($packageName)", ms)
            else ActionResult.fail("open_app($packageName)", "App not installed: $packageName", timeMs = ms)
        } catch (e: Exception) {
            ActionResult.fail("open_app($packageName)", e.message ?: "Unknown")
        }
    }

    // ── Action Command Dispatch ─────────────────────────────────────

    /**
     * Dispatch a single action command string.
     * Supports the same syntax as VoiceAccessibilityService.executeSingleAction.
     */
    suspend fun executeCommand(cmd: String): ActionResult {
        val lower = cmd.trim().lowercase()
        return when {
            lower.startsWith("tap ") -> tapText(cmd.removePrefix("tap ").trim())
            lower.startsWith("double tap ") -> doubleTap(cmd.removePrefix("double tap ").trim())
            lower.startsWith("type ") -> typeText(cmd.removePrefix("type ").trim())
            lower.startsWith("scroll ") -> scroll(cmd.removePrefix("scroll ").trim())
            lower.startsWith("swipe ") -> {
                val parts = cmd.removePrefix("swipe ").trim().split(Regex("\\s+"))
                if (parts.size >= 4) swipe(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0,
                    parts[2].toIntOrNull() ?: 0, parts[3].toIntOrNull() ?: 0)
                else ActionResult.fail("swipe", "Expected 4 coordinates, got ${parts.size}")
            }
            lower.startsWith("wait ") -> {
                val ms = cmd.removePrefix("wait ").trim().toLongOrNull() ?: 1000L
                delay(ms)
                ActionResult.ok("wait($ms ms)", ms)
            }
            lower.startsWith("open app ") -> openApp(cmd.removePrefix("open app ").trim())
            lower == "back" || lower == "go back" -> goBack()
            lower == "home" || lower == "go home" -> goHome()
            lower == "recents" || lower == "recent" -> openRecents()
            lower == "notifications" -> openNotifications()
            lower == "quick settings" -> openQuickSettings()
            lower == "clear" || lower == "clear text" -> clearText()
            else -> ActionResult.fail(cmd, "Unknown action: $cmd")
        }
    }
}
