package com.example.openvoice.system

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.example.openvoice.ai.DeviceProfiler
import com.example.openvoice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ResourceManager — Adaptive scheduling based on device state.
 *
 * Adjusts assistant behavior based on:
 * - Battery level
 * - Thermal state
 * - Charging status
 * - Available RAM
 * - CPU load
 *
 * The Planner uses this to choose lighter models or defer expensive work.
 */
@Singleton
class ResourceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiler: DeviceProfiler
) {
    data class ResourceState(
        val batteryPercent: Int = 100,
        val isCharging: Boolean = true,
        val thermalState: DeviceProfiler.ThermalStatus = DeviceProfiler.ThermalStatus.COOL,
        val availableRamMb: Long = 4096,
        val cpuCores: Int = 4,
        val cpuLoadPercent: Float = 0f
    )

    enum class PerformanceTier {
        MAXIMUM,    // Charging + cool + plenty of RAM
        HIGH,       // Good battery, normal thermals
        BALANCED,   // Default
        POWER_SAVE, // Low battery or warm
        MINIMUM     // Critical battery or hot — only essential operations
    }

    private var lastState: ResourceState? = null

    /**
     * Test seam for exercising resource-tier decisions without mutating device state.
     */
    internal var resourceStateOverride: (() -> ResourceState)? = null

    /**
     * Get the current resource state.
     */
    suspend fun getResourceState(): ResourceState = withContext(Dispatchers.IO) {
        val overridden = resourceStateOverride?.invoke()
        if (overridden != null) {
            lastState = overridden
            return@withContext overridden
        }

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val batteryPercent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val isCharging = batteryManager?.let {
            it.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
        } ?: true
        val thermalState = detectThermalState(powerManager)

        val caps = profiler.getCapabilities()
        val cpuLoad = estimateCpuLoad()

        ResourceState(
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            thermalState = thermalState,
            availableRamMb = caps.availableRamMb,
            cpuCores = caps.cpuCores,
            cpuLoadPercent = cpuLoad
        ).also { lastState = it }
    }

    /**
     * Thermal state via PowerManager#getCurrentThermalStatus (API 29+; minSdk is 26).
     */
    private fun detectThermalState(powerManager: PowerManager?): DeviceProfiler.ThermalStatus {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return DeviceProfiler.ThermalStatus.UNKNOWN
        }
        val status = powerManager?.currentThermalStatus ?: return DeviceProfiler.ThermalStatus.UNKNOWN
        // Subject-less when (boolean conditions) avoids lint's SwitchIntDef
        // enumeration of the THERMAL_STATUS IntDef; EMERGENCY/SHUTDOWN (API
        // 30/31) fall through to CRITICAL via else.
        return try {
            when {
                status == PowerManager.THERMAL_STATUS_NONE ||
                    status == PowerManager.THERMAL_STATUS_LIGHT ->
                    DeviceProfiler.ThermalStatus.COOL
                status == PowerManager.THERMAL_STATUS_MODERATE ->
                    DeviceProfiler.ThermalStatus.WARM
                status == PowerManager.THERMAL_STATUS_SEVERE ->
                    DeviceProfiler.ThermalStatus.HOT
                else -> DeviceProfiler.ThermalStatus.CRITICAL
            }
        } catch (_: Exception) { DeviceProfiler.ThermalStatus.UNKNOWN }
    }

    /**
     * Determine the current performance tier.
     */
    suspend fun getPerformanceTier(): PerformanceTier {
        val state = getResourceState()
        return when {
            // Minimum: critical battery or hot
            state.batteryPercent < 10 || state.thermalState == DeviceProfiler.ThermalStatus.CRITICAL ->
                PerformanceTier.MINIMUM
            // Power save: low battery or warm
            state.batteryPercent < 25 || state.thermalState == DeviceProfiler.ThermalStatus.HOT
                || state.thermalState == DeviceProfiler.ThermalStatus.WARM ->
                PerformanceTier.POWER_SAVE
            // Maximum: charging + cool + good RAM
            state.isCharging && state.thermalState == DeviceProfiler.ThermalStatus.COOL
                && state.availableRamMb > 2048 ->
                PerformanceTier.MAXIMUM
            // High: not charging but good resources
            state.availableRamMb > 1024 && state.cpuLoadPercent < 0.6f ->
                PerformanceTier.HIGH
            // Default
            else -> PerformanceTier.BALANCED
        }
    }

    /**
     * Get recommended AI config based on resource state.
     */
    suspend fun getRecommendedConfig(): AiConfigOverride = withContext(Dispatchers.IO) {
        val tier = getPerformanceTier()
        when (tier) {
            PerformanceTier.MINIMUM -> AiConfigOverride(
                maxContextLength = 512, maxTokens = 64, threads = 1,
                useVision = false, useLlm = false)
            PerformanceTier.POWER_SAVE -> AiConfigOverride(
                maxContextLength = 1024, maxTokens = 128, threads = 1,
                useVision = false, useLlm = true)
            PerformanceTier.BALANCED -> AiConfigOverride(
                maxContextLength = 2048, maxTokens = 256, threads = 2,
                useVision = false, useLlm = true)
            PerformanceTier.HIGH -> AiConfigOverride(
                maxContextLength = 4096, maxTokens = 512, threads = 4,
                useVision = true, useLlm = true)
            PerformanceTier.MAXIMUM -> AiConfigOverride(
                maxContextLength = 8192, maxTokens = 1024, threads = 6,
                useVision = true, useLlm = true)
        }
    }

    data class AiConfigOverride(
        val maxContextLength: Int = 2048,
        val maxTokens: Int = 256,
        val threads: Int = 2,
        val useVision: Boolean = false,
        val useLlm: Boolean = true
    )

    /**
     * Check if it's safe to run a heavy AI operation now.
     */
    suspend fun canRunHeavyInference(): Boolean {
        val tier = getPerformanceTier()
        return tier != PerformanceTier.MINIMUM && tier != PerformanceTier.POWER_SAVE
    }

    /**
     * Get current power summary for Developer Console.
     */
    suspend fun getPowerSummary(): String = withContext(Dispatchers.IO) {
        val state = getResourceState()
        val tier = getPerformanceTier()
        buildString {
            appendLine("🔋 ${state.batteryPercent}% ${if (state.isCharging) "⚡" else ""}")
            appendLine("🌡 ${state.thermalState}")
            appendLine("💾 ${state.availableRamMb}MB free")
            appendLine("⚙ Tier: $tier (${state.cpuCores} cores, ${"%.0f".format(state.cpuLoadPercent * 100)}% load)")
        }.trimEnd()
    }

    private fun estimateCpuLoad(): Float {
        // Simplified: read /proc/stat or use a heuristic
        // In production, parse /proc/stat for actual CPU utilization
        return 0.3f
    }
}
