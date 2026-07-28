package com.example.openvoice.task

import com.example.openvoice.accessibility.UiNode
import com.example.openvoice.intent.IntentResult
import com.example.openvoice.router.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TaskBlackboard — The single shared context object that every subsystem reads and writes.
 *
 * Instead of passing fragmented data between Voice→Intent→Accessibility→Memory→Planner,
 * all subsystems contribute to and read from this single task object.
 *
 * Example flow for "Reply to this message and then open Spotify":
 *   1. Voice writes: userGoal="Reply to this message and then open Spotify"
 *   2. STT writes: transcript="Reply to this message and then open Spotify"
 *   3. Intent writes: intent=LAUNCH_APP, entities={app: spotify}
 *   (but wait, the user said "reply to THIS message" first)
 *   4. CapabilityRouter sees: hasCurrentApp=true → uses Accessibility
 *   5. Accessibility writes: currentApp="Messages", actionHistory=["opened conversation"]
 *   6. Action "reply to message" completes ✓
 *   7. Blackboard advances: currentStep="Launch Spotify"
 *   8. CapabilityRouter resolves LAUNCH_APP natively
 *   9. Action "open Spotify" completes ✓
 *   10. Blackboard: completionStatus=COMPLETED
 */
data class TaskState(
    // ── Original Request ────────────────────────────────────────────
    val userGoal: String = "",
    val transcript: String = "",

    // ── Intent ──────────────────────────────────────────────────────
    val intentResult: IntentResult? = null,
    val resolution: Resolution? = null,

    // ── Screen Context ──────────────────────────────────────────────
    val currentApp: String = "",
    val currentActivity: String = "",
    val currentScreen: String = "",
    val uiTree: UiNode? = null,
    val keyboardVisible: Boolean = false,
    val activeDialogs: List<String> = emptyList(),

    // ── Vision (populated by Phase 5) ───────────────────────────────
    val visionResults: List<String> = emptyList(),
    val ocrText: String = "",

    // ── Memory (populated by Phase 6) ───────────────────────────────
    val memoryHits: List<String> = emptyList(),
    val memoryQueries: List<String> = emptyList(),

    // ── Planner State (populated by Phase 7) ────────────────────────
    val plan: List<String> = emptyList(),
    val currentStepIndex: Int = 0,
    val planComplete: Boolean = false,

    // ── Action History ──────────────────────────────────────────────
    val actionHistory: List<ActionResult> = emptyList(),
    val lastActionResult: ActionResult? = null,
    val retryCount: Int = 0,

    // ── Completion ──────────────────────────────────────────────────
    val completionStatus: CompletionStatus = CompletionStatus.PENDING,
    val errorMessage: String = "",
    val startedAt: Long = 0L,
    val completedAt: Long = 0L,
    val totalElapsedMs: Long = 0L
)

data class ActionResult(
    val action: String,
    val success: Boolean,
    val failureReason: String = "",
    val retrySuggestion: String = "",
    val executionTimeMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

enum class CompletionStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Singleton
class TaskBlackboard @Inject constructor() {

    private val _state = MutableStateFlow(TaskState())
    val state: StateFlow<TaskState> = _state

    // ── Request ─────────────────────────────────────────────────────

    fun startNewTask(userGoal: String, transcript: String) {
        _state.value = TaskState(
            userGoal = userGoal,
            transcript = transcript,
            startedAt = System.currentTimeMillis(),
            completionStatus = CompletionStatus.IN_PROGRESS
        )
    }

    // ── Intent ──────────────────────────────────────────────────────

    fun setIntent(intent: IntentResult, resolution: Resolution) {
        _state.value = _state.value.copy(
            intentResult = intent,
            resolution = resolution
        )
    }

    // ── Screen Context ──────────────────────────────────────────────

    fun setCurrentApp(app: String) {
        _state.value = _state.value.copy(currentApp = app)
    }

    fun setCurrentActivity(activity: String) {
        _state.value = _state.value.copy(currentActivity = activity)
    }

    fun setCurrentScreen(screen: String) {
        _state.value = _state.value.copy(currentScreen = screen)
    }

    fun setUiTree(tree: UiNode?) {
        _state.value = _state.value.copy(uiTree = tree)
    }

    fun setKeyboardVisible(visible: Boolean) {
        _state.value = _state.value.copy(keyboardVisible = visible)
    }

    fun setActiveDialogs(dialogs: List<String>) {
        _state.value = _state.value.copy(activeDialogs = dialogs)
    }

    // ── Vision ──────────────────────────────────────────────────────

    fun setOcrText(text: String) {
        _state.value = _state.value.copy(ocrText = text)
    }

    fun setVisionResults(results: List<String>) {
        _state.value = _state.value.copy(visionResults = results)
    }

    // ── Memory ──────────────────────────────────────────────────────

    fun setMemoryHits(hits: List<String>) {
        _state.value = _state.value.copy(memoryHits = hits)
    }

    // ── Planner ─────────────────────────────────────────────────────

    fun setPlan(plan: List<String>) {
        _state.value = _state.value.copy(
            plan = plan,
            currentStepIndex = 0,
            planComplete = false
        )
    }

    fun advancePlan(): Boolean {
        val state = _state.value
        val nextIndex = state.currentStepIndex + 1
        if (nextIndex >= state.plan.size) {
            _state.value = state.copy(
                currentStepIndex = state.plan.size,
                planComplete = true,
                completionStatus = CompletionStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                totalElapsedMs = System.currentTimeMillis() - state.startedAt
            )
            return false // Plan complete
        }
        _state.value = state.copy(currentStepIndex = nextIndex)
        return true
    }

    /** Get the current step in the plan. */
    fun currentPlanStep(): String? {
        val state = _state.value
        return state.plan.getOrNull(state.currentStepIndex)
    }

    // ── Actions ─────────────────────────────────────────────────────

    fun recordAction(result: ActionResult) {
        val current = _state.value
        _state.value = current.copy(
            actionHistory = current.actionHistory + result,
            lastActionResult = result
        )
    }

    fun recordSuccess(action: String, executionTimeMs: Long) {
        recordAction(ActionResult(
            action = action,
            success = true,
            executionTimeMs = executionTimeMs,
            timestamp = System.currentTimeMillis()
        ))
    }

    fun recordFailure(action: String, reason: String, retrySuggestion: String = "", executionTimeMs: Long = 0) {
        val current = _state.value
        _state.value = current.copy(
            retryCount = current.retryCount + 1,
            lastActionResult = ActionResult(
                action = action,
                success = false,
                failureReason = reason,
                retrySuggestion = retrySuggestion,
                executionTimeMs = executionTimeMs,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    // ── Completion ──────────────────────────────────────────────────

    fun markCompleted() {
        val current = _state.value
        _state.value = current.copy(
            completionStatus = CompletionStatus.COMPLETED,
            completedAt = System.currentTimeMillis(),
            totalElapsedMs = System.currentTimeMillis() - current.startedAt
        )
    }

    fun markFailed(error: String) {
        val current = _state.value
        _state.value = current.copy(
            completionStatus = CompletionStatus.FAILED,
            errorMessage = error,
            completedAt = System.currentTimeMillis(),
            totalElapsedMs = System.currentTimeMillis() - current.startedAt
        )
    }

    fun markCancelled() {
        val current = _state.value
        _state.value = current.copy(
            completionStatus = CompletionStatus.CANCELLED,
            completedAt = System.currentTimeMillis(),
            totalElapsedMs = System.currentTimeMillis() - current.startedAt
        )
    }

    // ── Query Methods ───────────────────────────────────────────────

    /** Check if the current task involves the current screen. */
    fun requiresCurrentScreen(): Boolean {
        val goal = _state.value.userGoal.lowercase()
        return goal.contains("this") || goal.contains("screen") ||
            goal.contains("here") || goal.contains("current")
    }

    /** Check if the user's request is fully satisfied by accessibility. */
    fun canCompleteWithA11y(): Boolean {
        // Returns true if there's a UI tree and no vision/memory is required
        val s = _state.value
        return s.uiTree != null &&
            s.visionResults.isEmpty() &&
            !s.planComplete
    }

    /** Get the summary timeline for Developer Console. */
    fun getTimeline(): String = buildString {
        val s = _state.value
        appendLine("🎯 Goal: ${s.userGoal}")
        appendLine("📱 App: ${s.currentApp}")
        appendLine("📝 Transcript: ${s.transcript}")
        appendLine("🎯 Intent: ${s.intentResult?.intent ?: "—"}")
        appendLine("🧠 Route: ${s.resolution?.let {
            when (it) {
                is com.example.openvoice.router.Resolution.Native -> "Native: ${it.operatorId}"
                is com.example.openvoice.router.Resolution.Accessibility -> "Accessibility"
                else -> it.javaClass.simpleName
            }
        } ?: "—"}")
        appendLine("📱 Action History: ${s.actionHistory.size} actions")
        if (s.lastActionResult != null) {
            val last = s.lastActionResult
            appendLine("   Last: ${if (last.success) "✓" else "✗"} ${last.action} (${last.executionTimeMs}ms)")
        }
        appendLine("   Steps: ${s.currentStepIndex}/${s.plan.size}")
        appendLine("🏁 Status: ${s.completionStatus}")
        if (s.totalElapsedMs > 0) appendLine("⏱ Elapsed: ${s.totalElapsedMs}ms")
    }

    fun reset() {
        _state.value = TaskState()
    }
}
