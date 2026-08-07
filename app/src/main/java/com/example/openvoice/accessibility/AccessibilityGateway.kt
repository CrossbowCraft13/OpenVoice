package com.example.openvoice.accessibility

/**
 * Slim gateway over [VoiceAccessibilityService] exposing exactly what the
 * accessibility engines (ActionEngine, ScreenStateManager, WorkflowEngine,
 * AccessibilityIntelligence) need.
 *
 * VoiceAccessibilityService implements this; tests can inject a scripted fake
 * so engine logic is exercised deterministically without a live accessibility
 * service (which cannot be attached in an instrumented test).
 */
interface AccessibilityGateway {
    fun getUiTree(): UiNode?
    fun getActivePackage(): String?

    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun tapText(text: String): Boolean
    suspend fun longPress(x: Int, y: Int): Boolean
    suspend fun type(text: String): Boolean
    suspend fun scroll(direction: String): Boolean
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean

    suspend fun goBack(): Boolean
    suspend fun goHome(): Boolean
    suspend fun openRecents(): Boolean
    suspend fun openNotifications(): Boolean
    suspend fun openQuickSettings(): Boolean
    suspend fun openApp(packageName: String): Boolean
}
