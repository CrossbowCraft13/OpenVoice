package com.example.openvoice.router

import com.example.openvoice.accessibility.VoiceAccessibilityService
import com.example.openvoice.intent.IntentResult
import com.example.openvoice.operator.OperatorResult
import com.example.openvoice.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capability Router — decides THE CHEAPEST PATH to fulfill a user request.
 *
 * Resolution order (fastest/cheapest first):
 *
 *   1. Native Android APIs (intents, SmsManager, AudioManager) — µs
 *   2. Accessibility Service — ms
 *   3. OCR (ML Kit text recognition) — 10–100ms
 *   4. Local LLM / Embeddings — 100ms–1s
 *   5. Vision Model (multimodal GGUF) — 1–5s
 *   6. Multi-step Planner — seconds+
 *   7. Large LLM reasoning — 5–30s
 *
 * Each step is attempted only if the previous ones cannot fulfill the request.
 * Vision is ONLY selected when Accessibility AND OCR are insufficient.
 */
sealed class Resolution {
    /** Request satisfied immediately via Android APIs */
    data class Native(val operatorId: String, val params: Map<String, String>) : Resolution()
    /** Request requires UI interaction via Accessibility Service */
    data class Accessibility(val commands: List<String>) : Resolution()
    /** Request needs local LLM inference */
    data class LocalLm(val prompt: String, val contextScreen: String = "") : Resolution()
    /** Request needs vision analysis (only when Accessibility is insufficient) */
    data class Vision(val prompt: String) : Resolution()
    /** Request needs multi-step planning */
    data class Planner(val goal: String) : Resolution()
    /** Request cannot be handled */
    data class Unsupported(val reason: String) : Resolution()
}

@Singleton
class CapabilityRouter @Inject constructor() {

    /**
     * Resolve a classified intent to the cheapest capable subsystem.
     * Uses the Accessibility service state to inform routing decisions.
     */
    fun resolve(intentResult: IntentResult): Resolution {
        val intent = intentResult.intent
        val entities = intentResult.entities
        val a11yAvailable = VoiceAccessibilityService.isRunning()

        // ── Tier 1: Fast-path Native Android API operators ──────────────
        val nativeIntents = setOf(
            "LAUNCH_APP", "SEND_SMS", "MAKE_CALL",
            "SET_TIMER", "SET_ALARM", "OPEN_SETTINGS",
            "ADJUST_VOLUME", "HELP", "STOP", "OPEN_NOTIFICATIONS"
        )
        if (intent in nativeIntents) {
            Logger.d("Router → Native: $intent $entities", "Router")
            return Resolution.Native(intent, entities)
        }

        // ── Tier 2: Accessibility gestures ───────────────────────────────
        // Pure screen-interaction commands are accessibility gestures and route
        // there even if the service is momentarily disconnected (it can be
        // (re)started on demand). READ_SCREEN/LAUNCH_APP are handled below.
        when (intent) {
            "NAVIGATE_BACK" -> return Resolution.Accessibility(listOf("back"))
            "NAVIGATE_HOME" -> return Resolution.Accessibility(listOf("home"))
            "SCROLL" -> {
                val dir = entities["direction"] ?: "down"
                return Resolution.Accessibility(listOf("scroll $dir"))
            }
            "SCREENSHOT" -> return Resolution.Accessibility(listOf("screenshot"))
        }

        // ── Tier 3: Accessibility Service (requires it to be running) ─────
        // Note: LAUNCH_APP never reaches here — Tier 1 handles it natively.
        if (a11yAvailable) {
            if (intent == "READ_SCREEN") {
                // Use Accessibility first. Only escalate to Vision if needed.
                Logger.d("Router → Accessibility: describe screen", "Router")
                return Resolution.Accessibility(listOf("describe_screen"))
            }

            // Check if we need UI interaction for "this" references
            if (intentResult.requiresClarification) {
                Logger.d("Router → Accessibility: screen context needed", "Router")
                return Resolution.Accessibility(listOf("describe_screen"))
            }
        }

        // ── Tier 3: Local LLM ─────────────────────────────────────────
        if (intent == "QUERY") {
            val query = entities["query"] ?: entities.values.firstOrNull() ?: ""
            Logger.d("Router → Local LLM: $query", "Router")
            return Resolution.LocalLm(query)
        }

        // ── Tier 4: Vision (only when accessibility unavailable) ───────
        if (intent == "READ_SCREEN" && !a11yAvailable) {
            Logger.d("Router → Vision: accessibility unavailable", "Router")
            return Resolution.Vision("Describe what's on the screen")
        }

        // ── Tier 5: Unsupported ────────────────────────────────────────
        if (intent == "UNKNOWN" && intentResult.requiresClarification) {
            return Resolution.Unsupported("I couldn't understand that request. " +
                "Try rephrasing, or ask for help to see available commands.")
        }

        Logger.w("Router → Unsupported: $intent", "Router")
        return Resolution.Unsupported("No handler for intent: $intent")
    }
}
