package com.example.openvoice.system.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.openvoice.util.Logger
import com.example.openvoice.util.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OnboardingFlow — Guided setup experience for first-time users.
 *
 * Walks through:
 * 1. Welcome & privacy commitment
 * 2. Microphone permission (required for voice)
 * 3. Notification permission (required for foreground service)
 * 4. Accessibility service (optional, for app automation)
 * 5. Battery optimization (optional, for reliability)
 * 6. Completion
 *
 * Explains WHY each permission is needed before requesting it.
 */
@Singleton
class OnboardingFlow @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: PermissionManager
) {
    data class OnboardingStep(
        val id: String,
        val title: String,
        val description: String,
        val explanation: String,        // WHY this permission is needed
        val isRequired: Boolean,
        val permission: String? = null, // Android permission string
        val settingsAction: String? = null // System settings intent action
    )

    data class OnboardingState(
        val currentStep: Int = 0,
        val totalSteps: Int = 6,
        val completed: Boolean = false,
        val steps: List<OnboardingStepStatus> = emptyList()
    )

    data class OnboardingStepStatus(
        val stepId: String,
        val completed: Boolean,
        val skipped: Boolean = false
    )

    val steps = listOf(
        OnboardingStep(
            id = "welcome",
            title = "Welcome to OpenVoice",
            description = "Your private, local voice assistant",
            explanation = "OpenVoice runs entirely on your device. No cloud services. " +
                "No telemetry. No data ever leaves your phone. Everything is encrypted at rest.",
            isRequired = true
        ),
        OnboardingStep(
            id = "microphone",
            title = "Microphone Access",
            description = "Required for voice commands",
            explanation = "OpenVoice needs microphone access to hear your voice commands. " +
                "Audio is processed locally on your device using on-device AI — " +
                "it is never recorded, stored, or sent to any server.",
            isRequired = true,
            permission = Manifest.permission.RECORD_AUDIO
        ),
        OnboardingStep(
            id = "notifications",
            title = "Notifications",
            description = "Required for background operation",
            explanation = "OpenVoice runs as a foreground service so it can listen for " +
                "wake words even when the app is in the background. A persistent " +
                "notification is required by Android for foreground services.",
            isRequired = true,
            permission = Manifest.permission.POST_NOTIFICATIONS
        ),
        OnboardingStep(
            id = "accessibility",
            title = "Accessibility Service",
            description = "Optional — enables app automation",
            explanation = "The accessibility service allows OpenVoice to read your screen " +
                "and perform actions in other apps (like tapping buttons or filling forms). " +
                "This is optional. All processing is local and private.",
            isRequired = false,
            settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS
        ),
        OnboardingStep(
            id = "battery",
            title = "Battery Optimization",
            description = "Optional — improves reliability",
            explanation = "Disabling battery optimization for OpenVoice helps ensure the " +
                "assistant stays active in the background and responds reliably to wake words.",
            isRequired = false,
            settingsAction = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        ),
        OnboardingStep(
            id = "complete",
            title = "You're All Set!",
            description = "OpenVoice is ready to use",
            explanation = "Try saying \"Hey OpenVoice\" followed by a command like " +
                "\"Open Spotify\" or \"Set a timer for 5 minutes.\" " +
                "All processing is local. Your data stays on your device.",
            isRequired = false
        )
    )

    /**
     * Check the current onboarding state (which steps are done).
     */
    fun getState(): OnboardingState {
        val statuses = steps.map { step ->
            when (step.id) {
                "welcome" -> OnboardingStepStatus("welcome", true)
                "microphone" -> OnboardingStepStatus("microphone",
                    permissionManager.isAudioGranted())
                "notifications" -> OnboardingStepStatus("notifications",
                    permissionManager.isNotificationGranted())
                "accessibility" -> OnboardingStepStatus("accessibility",
                    permissionManager.isAccessibilityEnabled())
                "battery" -> OnboardingStepStatus("battery", true) // Always skipable
                "complete" -> OnboardingStepStatus("complete", false)
                else -> OnboardingStepStatus(step.id, false)
            }
        }

        val firstIncomplete = statuses.indexOfFirst { !it.completed }
        val allDone = statuses.all { it.completed }

        return OnboardingState(
            currentStep = if (allDone) statuses.size - 1 else firstIncomplete.coerceAtLeast(0),
            completed = allDone,
            steps = statuses
        )
    }

    /**
     * Get the explanation text for a given step.
     */
    fun getExplanation(stepId: String): String? = steps.find { it.id == stepId }?.explanation

    /**
     * Check if onboarding is complete.
     */
    fun isOnboardingComplete(): Boolean = getState().completed
}
