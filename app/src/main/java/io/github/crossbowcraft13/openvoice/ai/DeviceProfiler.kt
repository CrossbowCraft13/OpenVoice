package io.github.crossbowcraft13.openvoice.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import io.github.crossbowcraft13.openvoice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeviceProfiler — automatically detects device capabilities
 * and recommends an appropriate local LLM model size.
 *
 * Detection targets:
 * - CPU architecture & cores
 * - Total RAM
 * - GPU availability (Vulkan, NNAPI)
 * - Android API level
 * - Free storage
 * - Thermal state
 *
 * Model recommendation (conservative):
 *   < 4 GB → SmolLM (soft fail, models won't run well)
 *   4–6 GB → Qwen2 0.5B / SmolLM 1.7B
 *   6–8 GB → Gemma 2B / Phi-3 Mini
 *   8–12 GB → Qwen2 1.5B / Gemma 2B
 *   12–16 GB → Qwen2 4B / Mistral 7B (4-bit quantized)
 *   16+ GB  → Llama 3.2 8B / Qwen2 7B (4-bit quantized)
 */
@Singleton
class DeviceProfiler @Inject constructor(
    @ApplicationContext private val context: Context?
) {
    data class DeviceCapabilities(
        val totalRamMb: Long,
        val availableRamMb: Long,
        val cpuCores: Int,
        val cpuArch: String,
        val hasVulkan: Boolean,
        val hasNnapi: Boolean,
        val androidApiLevel: Int,
        val freeStorageMb: Long,
        val isLowRamDevice: Boolean,
        val thermalStatus: ThermalStatus
    )

    enum class ThermalStatus { COOL, WARM, HOT, CRITICAL, UNKNOWN }
    enum class ModelTier {
        NONE(0),           // Cannot run any model
        TINY(1_000_000_000),    // 1B params max
        SMALL(3_000_000_000),   // 3B params max
        MEDIUM(7_000_000_000),  // 7B params max
        LARGE(13_000_000_000);  // 13B params max

        val maxParams: Long
        constructor(maxParams: Long) { this.maxParams = maxParams }
    }

    data class Recommendation(
        val tier: ModelTier,
        val suggestedModel: String,
        val maxContextLength: Int,
        val suggestedThreads: Int,
        val reason: String
    )

    fun getCapabilities(): DeviceCapabilities {
        val memInfo = ActivityManager.MemoryInfo()
        val activityManager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager == null) {
            // No Android environment (e.g. JVM unit tests): report safe defaults
            // rather than crashing — the model recommendation then falls to NONE.
            return DeviceCapabilities(
                totalRamMb = 0, availableRamMb = 0,
                cpuCores = Runtime.getRuntime().availableProcessors(),
                cpuArch = System.getProperty("os.arch") ?: "unknown",
                hasVulkan = false,
                hasNnapi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
                androidApiLevel = Build.VERSION.SDK_INT,
                freeStorageMb = 0, isLowRamDevice = false,
                thermalStatus = ThermalStatus.UNKNOWN
            )
        }
        activityManager.getMemoryInfo(memInfo)

        val cpuArch = System.getProperty("os.arch") ?: "unknown"
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val freeStorageMb = getFreeStorageMb()
        val hasVulkan = checkVulkanSupport()
        val hasNnapi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val thermalStatus = detectThermalStatus()

        return DeviceCapabilities(
            totalRamMb = memInfo.totalMem / (1024 * 1024),
            availableRamMb = memInfo.availMem / (1024 * 1024),
            cpuCores = cpuCores,
            cpuArch = cpuArch,
            hasVulkan = hasVulkan,
            hasNnapi = hasNnapi,
            androidApiLevel = Build.VERSION.SDK_INT,
            freeStorageMb = freeStorageMb,
            isLowRamDevice = activityManager.isLowRamDevice,
            thermalStatus = thermalStatus
        )
    }

    fun recommendModel(): Recommendation {
        val caps = getCapabilities()
        val ramGb = caps.totalRamMb / 1024.0
        val freeGb = caps.freeStorageMb / 1024.0

        return when {
            ramGb < 4 || caps.isLowRamDevice || freeGb < 3 ->
                Recommendation(ModelTier.NONE, "none", 0, 1,
                    "Insufficient resources (${"%.1f".format(ramGb)}GB RAM, ${"%.1f".format(freeGb)}GB free)")
            ramGb < 6 ->
                Recommendation(ModelTier.TINY, "Qwen2-0.5B-Instruct-Q4_K_M.gguf", 4096, 2,
                    "Tiny model: ${"%.1f".format(ramGb)}GB RAM")
            ramGb < 8 ->
                Recommendation(ModelTier.SMALL, "Gemma-2B-Instruct-Q4_K_M.gguf", 8192, 2,
                    "Small model: ${"%.1f".format(ramGb)}GB RAM")
            ramGb < 12 ->
                Recommendation(ModelTier.SMALL, "Phi-3-mini-4k-instruct-Q4_K_M.gguf", 8192, 4,
                    "Medium device: ${"%.1f".format(ramGb)}GB RAM")
            ramGb < 16 ->
                Recommendation(ModelTier.MEDIUM, "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf", 16384, 4,
                    "Large device: ${"%.1f".format(ramGb)}GB RAM")
            else ->
                Recommendation(ModelTier.LARGE, "Llama-3.2-8B-Instruct-Q4_K_M.gguf", 32768, 6,
                    "High-end device: ${"%.1f".format(ramGb)}GB RAM")
        }
    }

    fun estimateModelRequirements(modelParamsB: Float): ModelRequirements {
        // Rough estimates: Q4 quantized model ~0.6 GB per 1B params
        val modelGb = modelParamsB * 0.6f
        // KV cache for context: ~2MB per 1k tokens per 1B params
        val kvCacheGb = (8192f / 1000f) * modelParamsB * 0.002f
        val totalGb = modelGb + kvCacheGb + 0.5f // overhead
        return ModelRequirements(
            ramRequiredMb = (totalGb * 1024).toLong(),
            storageRequiredMb = (modelGb * 1200).toLong(), // Q4 file size
            recommendedThreads = when {
                Runtime.getRuntime().availableProcessors() >= 8 -> 6
                Runtime.getRuntime().availableProcessors() >= 6 -> 4
                else -> 2
            },
            initialContextTokens = 2048
        )
    }

    data class ModelRequirements(
        val ramRequiredMb: Long,
        val storageRequiredMb: Long,
        val recommendedThreads: Int,
        val initialContextTokens: Int
    )

    fun getSummary(): String = buildString {
        val c = getCapabilities()
        val r = recommendModel()
        appendLine("CPU: ${c.cpuArch} (${c.cpuCores} cores)")
        appendLine("RAM: ${c.totalRamMb}MB total / ${c.availableRamMb}MB free")
        appendLine("Storage: ${c.freeStorageMb}MB free")
        appendLine("GPU: Vulkan=${c.hasVulkan} NNAPI=${c.hasNnapi}")
        appendLine("Android: API ${c.androidApiLevel} (${if (c.isLowRamDevice) "low RAM" else "normal"})")
        appendLine("Thermal: ${c.thermalStatus}")
        appendLine("→ Recommended: ${r.suggestedModel}")
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private fun getFreeStorageMb(): Long {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val bytes = stat.availableBlocksLong * stat.blockSizeLong
            bytes / (1024 * 1024)
        } catch (e: Exception) { 0L }
    }

    private fun checkVulkanSupport(): Boolean {
        return try {
            // Check if Vulkan is available via PackageManager
            val pm = context?.packageManager
            pm?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) ?: false
        } catch (e: Exception) { false }
    }

    private fun detectThermalStatus(): ThermalStatus {
        // PowerManager#getCurrentThermalStatus requires API 29; minSdk is 26.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalStatus.UNKNOWN
        return try {
            val pm = context?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                ?: return ThermalStatus.UNKNOWN
            when (pm.currentThermalStatus) {
                android.os.PowerManager.THERMAL_STATUS_NONE, android.os.PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.COOL
                android.os.PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.WARM
                android.os.PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.HOT
                android.os.PowerManager.THERMAL_STATUS_CRITICAL, android.os.PowerManager.THERMAL_STATUS_EMERGENCY,
                    android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.CRITICAL
                else -> ThermalStatus.UNKNOWN
            }
        } catch (e: Exception) { ThermalStatus.UNKNOWN }
    }
}
