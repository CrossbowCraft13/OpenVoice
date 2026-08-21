package com.example.openvoice.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.example.openvoice.accessibility.VoiceAccessibilityService

/**
 * Manages all runtime permissions required by OpenVoice.
 * Provides a unified API to check, request, and recover permissions.
 */
class PermissionManager(private val context: Context) {

    enum class PermissionState {
        GRANTED,       // Permission is fully granted
        NOT_GRANTED,   // Permission has not been requested or was denied
        PERMANENTLY_DENIED  // User selected "Don't ask again"
    }

    data class PermissionStatus(
        val name: String,
        val state: PermissionState,
        val canRequest: Boolean  // false if permanently denied (need system settings)
    )

    companion object {
        // POST_NOTIFICATIONS is a compile-time String constant (inlined by the
        // compiler), so referencing it below API 33 is safe; isNotificationGranted()
        // and the request flows already gate on SDK_INT >= 33.
        @SuppressLint("InlinedApi")
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
        )

        fun audioPermission() = Manifest.permission.RECORD_AUDIO

        @SuppressLint("InlinedApi")
        fun notificationPermission() = Manifest.permission.POST_NOTIFICATIONS
    }

    // ── Permission Checking ──────────────────────────────────────────────────

    fun checkPermission(permission: String): PermissionState {
        return when {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED ->
                PermissionState.GRANTED
            shouldShowRationale(permission) -> PermissionState.NOT_GRANTED
            hasRequestedBefore(permission) && !shouldShowRationale(permission) ->
                PermissionState.PERMANENTLY_DENIED
            else -> PermissionState.NOT_GRANTED
        }
    }

    fun checkAllRequired(): List<PermissionStatus> {
        return REQUIRED_PERMISSIONS.map { perm ->
            PermissionStatus(
                name = perm.substringAfterLast('.'),
                state = checkPermission(perm),
                canRequest = checkPermission(perm) != PermissionState.PERMANENTLY_DENIED
            )
        }
    }

    fun isAudioGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isNotificationGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    // ── Accessibility Service ────────────────────────────────────────────────

    fun isAccessibilityEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.name == VoiceAccessibilityService::class.java.name
        }
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ── Battery Optimization ─────────────────────────────────────────────────

    fun openBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.e("Failed to open battery settings: ${e.message}", "Permissions")
        }
    }

    // ── System Permissions ────────────────────────────────────────────────────

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openNotificationSettings() {
        try {
            // ACTION_APP_NOTIFICATION_SETTINGS is API 26+; minSdk is 26.
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    // ── Permission Status Summary ─────────────────────────────────────────────

    fun getStatusSummary(): String {
        val all = checkAllRequired()
        val granted = all.count { it.state == PermissionState.GRANTED }
        val total = all.size
        val missing = all.filter { it.state != PermissionState.GRANTED }
            .joinToString(", ") { it.name }

        return buildString {
            appendLine("Permissions: $granted/$total granted")
            if (missing.isNotEmpty()) {
                appendLine("Missing: $missing")
            }
            appendLine("Accessibility: ${if (isAccessibilityEnabled()) "Enabled" else "Disabled"}")
        }.trim()
    }

    fun hasAllRequired(): Boolean =
        REQUIRED_PERMISSIONS.all { checkPermission(it) == PermissionState.GRANTED }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun shouldShowRationale(permission: String): Boolean {
        return try {
            val activity = context as? android.app.Activity
            activity?.shouldShowRequestPermissionRationale(permission) ?: false
        } catch (e: Exception) { false }
    }

    private fun hasRequestedBefore(permission: String): Boolean {
        val prefs = context.getSharedPreferences("openvoice_permissions", Context.MODE_PRIVATE)
        return prefs.getBoolean("requested_$permission", false)
    }

    fun markRequested(permission: String) {
        val prefs = context.getSharedPreferences("openvoice_permissions", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("requested_$permission", true).apply()
    }
}
