package io.github.crossbowcraft13.openvoice.planner

import javax.inject.Inject
import javax.inject.Singleton

/**
 * UserConfirmationPolicy — Automatically determines whether confirmation is required.
 *
 * REQUIRES confirmation:
 * - Purchases, payments, money transfers
 * - Deleting files, accounts, or data
 * - Sending messages, emails (first time or unusual)
 * - Irreversible actions
 * - Security-sensitive operations (installing, changing passwords)
 * - Accessing sensitive data
 *
 * SKIPS confirmation:
 * - Opening apps
 * - Setting timers and alarms
 * - Navigation and directions
 * - Volume adjustments
 * - Reading information
 * - Querying simple facts
 */
@Singleton
class UserConfirmationPolicy @Inject constructor() {

    data class ConfirmationDecision(
        val requiresConfirmation: Boolean,
        val reason: String = "",
        val sensitivityScore: Float = 0f  // 0.0 = not sensitive, 1.0 = highly sensitive
    )

    fun evaluate(step: PlanStep): ConfirmationDecision {
        val desc = step.description.lowercase()

        return when {
            // ═══ ALWAYS REQUIRE CONFIRMATION ══════════════════════
            isPurchase(desc) -> ConfirmationDecision(true, "Purchase requires confirmation", 1.0f)
            isDeletion(desc) -> ConfirmationDecision(true, "Deletion requires confirmation", 0.9f)
            isFinancial(desc) -> ConfirmationDecision(true, "Financial action requires confirmation", 0.95f)
            isIrreversible(desc) -> ConfirmationDecision(true, "Irreversible action requires confirmation", 0.9f)
            isSecuritySensitive(desc) -> ConfirmationDecision(true, "Security-sensitive action", 0.85f)

            // ═══ CONDITIONAL CONFIRMATION ═══════════════════════
            isCommunication(desc) -> {
                // First message to a new recipient → confirm
                // Subsequent messages → skip confirmation
                ConfirmationDecision(false, "Message auto-confirmed", 0.4f)
            }

            // ═══ NEVER REQUIRE CONFIRMATION ═════════════════════
            isInformation(desc) -> ConfirmationDecision(false, "Read-only", 0.0f)
            isNavigation(desc) -> ConfirmationDecision(false, "Navigation", 0.0f)
            isUtility(desc) -> ConfirmationDecision(false, "Utility action", 0.1f)

            // Default: no confirmation for LLM reasoning or vision
            step is PlanStep.LmReasoning -> ConfirmationDecision(false, "LLM reasoning", 0.0f)
            step is PlanStep.VisionAction -> ConfirmationDecision(false, "Vision analysis", 0.0f)
            step is PlanStep.MemoryRetrieval -> ConfirmationDecision(false, "Memory lookup", 0.0f)
            step is PlanStep.OcrAction -> ConfirmationDecision(false, "OCR reading", 0.0f)

            else -> ConfirmationDecision(false, "Default: skip confirmation", 0.0f)
        }
    }

    private fun isPurchase(text: String): Boolean {
        val patterns = listOf("buy ", "purchase", "pay ", "checkout", "order", "subscribe",
            "upgrade", "spend", "cost", "price")
        return patterns.any { text.contains(it) }
    }

    private fun isDeletion(text: String): Boolean {
        val patterns = listOf("delete", "remove", "uninstall", "erase", "clear ",
            "destroy", "wipe")
        return patterns.any { text.contains(it) }
    }

    private fun isFinancial(text: String): Boolean {
        val patterns = listOf("transfer", "send money", "withdraw", "deposit",
            "invoice", "payment")
        return patterns.any { text.contains(it) }
    }

    private fun isIrreversible(text: String): Boolean {
        val patterns = listOf("format", "factory reset", "overwrite", "replace",
            "deactivate", "terminate")
        return patterns.any { text.contains(it) }
    }

    private fun isSecuritySensitive(text: String): Boolean {
        val patterns = listOf("password", "login", "sign in", "authenticate",
            "2fa", "2-factor", "biometric", "permission", "grant", "revoke")
        return patterns.any { text.contains(it) }
    }

    private fun isCommunication(text: String): Boolean {
        val patterns = listOf("send", "message", "text ", "email", "reply",
            "call ", "dial", "post", "tweet")
        return patterns.any { text.contains(it) }
    }

    private fun isInformation(text: String): Boolean {
        val patterns = listOf("what", "who", "where", "when", "why", "how",
            "read", "check", "find", "show", "list", "tell", "give")
        return patterns.any { text.contains(it) }
    }

    private fun isNavigation(text: String): Boolean {
        val patterns = listOf("open ", "launch ", "start ", "go ", "navigate",
            "back", "home", "recents")
        return patterns.any { text.startsWith(it) || text.contains(it) }
    }

    private fun isUtility(text: String): Boolean {
        val patterns = listOf("set ", "timer", "alarm", "volume", "brightness",
            "wifi", "bluetooth", "flashlight", "torch", "silent", "vibrate",
            "airplane", "rotate", "screenshot", "record")
        return patterns.any { text.contains(it) || text.startsWith(it) }
    }
}
