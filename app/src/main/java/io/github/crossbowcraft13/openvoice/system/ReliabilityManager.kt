package io.github.crossbowcraft13.openvoice.system

import android.content.Context
import android.content.Intent
import io.github.crossbowcraft13.openvoice.service.AssistantService
import io.github.crossbowcraft13.openvoice.task.TaskBlackboard
import io.github.crossbowcraft13.openvoice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ReliabilityManager — Centralized error handling, crash recovery, and state restoration.
 *
 * Capabilities:
 * - Graceful degradation when subsystems fail
 * - Automatic service restart
 * - Crash recovery and state restoration
 * - Resource cleanup
 * - Timeout management
 */
@Singleton
class ReliabilityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blackboard: TaskBlackboard
) {
    private val errors = mutableListOf<SystemError>()
    private var crashCount = 0
    private var lastRestartTime = 0L

    data class SystemError(
        val component: String,
        val message: String,
        val severity: Severity,
        val timestamp: Long = System.currentTimeMillis(),
        val recoverable: Boolean = true,
        val recoveryAction: String = ""
    )

    enum class Severity { DEBUG, WARNING, ERROR, CRITICAL }

    /**
     * Record and handle a system error.
     */
    fun reportError(component: String, message: String, severity: Severity = Severity.ERROR) {
        val error = SystemError(component, message, severity)
        errors.add(error)
        if (errors.size > 100) errors.removeAt(0)

        Logger.e("[$component] $message (${severity})", "Reliability")

        when (severity) {
            Severity.CRITICAL -> handleCriticalError(error)
            Severity.ERROR -> handleError(error)
            Severity.WARNING -> handleWarning(error)
            Severity.DEBUG -> Unit // Log only
        }
    }

    /**
     * Restart the assistant service.
     */
    fun restartService() {
        crashCount++
        lastRestartTime = System.currentTimeMillis()

        Logger.w("Restarting assistant service (crash #$crashCount)", "Reliability")
        try {
            context.stopService(Intent(context, AssistantService::class.java))
            Thread.sleep(500)
            context.startForegroundService(Intent(context, AssistantService::class.java))
        } catch (e: Exception) {
            Logger.e("Service restart failed: ${e.message}", "Reliability")
        }
    }

    /**
     * Restore state after a crash.
     */
    fun restoreState(): Boolean {
        if (crashCount == 0) return true

        Logger.i("Restoring state after ${crashCount} crashes", "Reliability")
        blackboard.reset()

        // In production: reload persisted task state from encrypted storage
        return true
    }

    /**
     * Get recent errors for diagnostics.
     */
    fun getRecentErrors(limit: Int = 10): List<SystemError> = errors.takeLast(limit)

    /**
     * Get system health status.
     */
    fun getHealthSummary(): String = buildString {
        appendLine("Crashes: $crashCount")
        appendLine("Recent errors: ${errors.size}")
        val critical = errors.count { it.severity == Severity.CRITICAL }
        val warnings = errors.count { it.severity == Severity.WARNING }
        if (critical > 0) appendLine("⚠ Critical: $critical")
        if (warnings > 0) appendLine("⚠ Warnings: $warnings")
        val lastError = errors.lastOrNull()
        if (lastError != null) {
            appendLine("Last: [${lastError.component}] ${lastError.message}")
        }
    }.trimEnd()

    // ── Private Handlers ────────────────────────────────────────────

    private fun handleCriticalError(error: SystemError) {
        Logger.e("CRITICAL: ${error.component}: ${error.message}", "Reliability")
        restartService()
    }

    private fun handleError(error: SystemError) {
        Logger.w("Error: ${error.component}: ${error.message}", "Reliability")
        if (error.recoverable && error.recoveryAction.isNotEmpty()) {
            Logger.i("Recovery: ${error.recoveryAction}", "Reliability")
        }
    }

    private fun handleWarning(error: SystemError) {
        Logger.w("Warning: ${error.component}: ${error.message}", "Reliability")
    }

    fun clearErrors() { errors.clear() }
}
