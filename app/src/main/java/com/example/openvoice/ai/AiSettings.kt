package com.example.openvoice.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI Settings — persisted configuration for the local inference engine.
 * All settings are stored in DataStore and exposed as a data class.
 */
data class AiConfig(
    val activeModelPath: String = "",
    val modelName: String = "",
    val contextLength: Int = 2048,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val threads: Int = 2,
    val gpuAcceleration: Boolean = false,
    val streamingEnabled: Boolean = true,
    val batterySaverMode: Boolean = false,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val repeatPenalty: Float = 1.1f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val stopTokens: List<String> = listOf("</s>", "<|im_end|>"),
    val embeddingsEnabled: Boolean = true,
    val autoDownloadModels: Boolean = true,
    val maxStorageGb: Int = 10
)

@Singleton
class AiSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("openvoice_ai", Context.MODE_PRIVATE)

    private fun getString(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default
    private fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    private fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    private fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    fun load(): AiConfig = AiConfig(
        activeModelPath = getString("active_model_path"),
        modelName = getString("model_name"),
        contextLength = getInt("context_length", 2048),
        temperature = getFloat("temperature", 0.7f),
        maxTokens = getInt("max_tokens", 512),
        threads = getInt("threads", 2),
        gpuAcceleration = getBoolean("gpu_acceleration", false),
        streamingEnabled = getBoolean("streaming_enabled", true),
        batterySaverMode = getBoolean("battery_saver_mode", false),
        topK = getInt("top_k", 40),
        topP = getFloat("top_p", 0.95f),
        repeatPenalty = getFloat("repeat_penalty", 1.1f),
        frequencyPenalty = getFloat("frequency_penalty", 0.0f),
        presencePenalty = getFloat("presence_penalty", 0.0f),
        stopTokens = prefs.getStringSet("stop_tokens", setOf("</s>", "<|im_end|>"))?.toList()
            ?: listOf("</s>", "<|im_end|>"),
        embeddingsEnabled = getBoolean("embeddings_enabled", true),
        autoDownloadModels = getBoolean("auto_download_models", true),
        maxStorageGb = getInt("max_storage_gb", 10)
    )

    fun save(config: AiConfig) {
        prefs.edit()
            .putString("active_model_path", config.activeModelPath)
            .putString("model_name", config.modelName)
            .putInt("context_length", config.contextLength)
            .putFloat("temperature", config.temperature)
            .putInt("max_tokens", config.maxTokens)
            .putInt("threads", config.threads)
            .putBoolean("gpu_acceleration", config.gpuAcceleration)
            .putBoolean("streaming_enabled", config.streamingEnabled)
            .putBoolean("battery_saver_mode", config.batterySaverMode)
            .putInt("top_k", config.topK)
            .putFloat("top_p", config.topP)
            .putFloat("repeat_penalty", config.repeatPenalty)
            .putFloat("frequency_penalty", config.frequencyPenalty)
            .putFloat("presence_penalty", config.presencePenalty)
            .putStringSet("stop_tokens", config.stopTokens.toSet())
            .putBoolean("embeddings_enabled", config.embeddingsEnabled)
            .putBoolean("auto_download_models", config.autoDownloadModels)
            .putInt("max_storage_gb", config.maxStorageGb)
            .apply()
    }

    /** Apply battery-saver overrides (lower tokens, reduce threads). */
    fun applyBatterySaver(config: AiConfig): AiConfig =
        if (config.batterySaverMode) config.copy(
            maxTokens = (config.maxTokens * 0.5).toInt().coerceAtLeast(128),
            threads = 1,
            contextLength = config.contextLength.coerceAtMost(1024)
        ) else config
}
