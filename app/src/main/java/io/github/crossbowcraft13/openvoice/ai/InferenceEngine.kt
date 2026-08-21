package io.github.crossbowcraft13.openvoice.ai

import android.content.Context
import io.github.crossbowcraft13.openvoice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * InferenceEngine — THE single public interface for all AI inference.
 *
 * No future module (Memory, Vision, Planner, Plugin, Accessibility) should
 * communicate with llama.cpp or any other inference backend directly.
 * Everything goes through this interface.
 *
 * Capabilities:
 * - Prompt completion (sync + streaming)
 * - Chat completion
 * - Embedding generation
 * - Model loading / unloading
 * - Context management
 * - Cancellation
 */
sealed class InferenceResult {
    data class Success(val text: String, val tokensPerSecond: Float = 0f, val totalTokens: Int = 0) : InferenceResult()
    data class StreamProgress(val partial: String) : InferenceResult()
    data class Error(val message: String) : InferenceResult()
    object Cancelled : InferenceResult()
    object ModelNotLoaded : InferenceResult()
}

data class ChatMessage(
    val role: String,  // "system", "user", "assistant"
    val content: String
)

@Singleton
class InferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AiSettings,
    private val profiler: DeviceProfiler,
    // Default keeps direct-construction call sites (tests, benchmarks) working
    // unchanged; the DI graph always supplies this explicitly.
    private val backend: InferenceBackend = LlamaCppBridge
) {

    private var lastLoadAttempt = 0L

    /** Whether a model is currently loaded and ready. */
    val isReady: Boolean get() = backend.isLoaded()

    /** Whether inference is currently running. */
    val isProcessing: Boolean get() = backend.isProcessing()

    // ── Model Lifecycle ─────────────────────────────────────────────────

    /**
     * Load the currently configured model.
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        val config = settings.applyBatterySaver(settings.load())
        if (config.activeModelPath.isEmpty()) {
            Logger.w("No active model configured", "AI")
            return@withContext false
        }
        val modelFile = File(config.activeModelPath)
        if (!modelFile.exists()) {
            Logger.e("Model file not found: ${config.activeModelPath}", "AI")
            return@withContext false
        }
        lastLoadAttempt = System.currentTimeMillis()
        backend.loadModel(modelFile, config)
    }

    /**
     * Unload the current model and free resources.
     */
    fun unloadModel() {
        backend.release()
    }

    // ── Completion ──────────────────────────────────────────────────────

    /**
     * Complete a prompt (synchronous). Returns the generated text.
     */
    suspend fun complete(prompt: String): String = withContext(Dispatchers.IO) {
        if (!isReady) { Logger.w("Engine not ready", "AI"); return@withContext "" }
        val config = settings.applyBatterySaver(settings.load())
        backend.complete(prompt, config)
    }

    /**
     * Chat completion. Formats messages into a prompt, runs inference.
     */
    suspend fun chat(messages: List<ChatMessage>): InferenceResult = withContext(Dispatchers.IO) {
        if (!isReady) return@withContext InferenceResult.ModelNotLoaded
        val config = settings.load()
        val prompt = buildChatPrompt(messages, config.modelName)
        val text = backend.complete(prompt, config)
        if (text.isEmpty()) InferenceResult.Error("Empty response")
        else InferenceResult.Success(text)
    }

    /**
     * Streaming chat completion. Calls onToken for each generated token.
     */
    suspend fun chatStream(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit
    ): InferenceResult = withContext(Dispatchers.IO) {
        if (!isReady) return@withContext InferenceResult.ModelNotLoaded
        val config = settings.applyBatterySaver(settings.load())
        val prompt = buildChatPrompt(messages, config.modelName)
        val full = backend.completeStream(prompt, config, onToken)
        if (full.isEmpty() && isReady) InferenceResult.Error("Empty response")
        else InferenceResult.Success(full)
    }

    private fun buildChatPrompt(messages: List<ChatMessage>, modelName: String): String {
        val sb = StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                "system" -> sb.appendLine("<|im_start|>system\n${msg.content}<|im_end|>")
                "user" -> sb.appendLine("<|im_start|>user\n${msg.content}<|im_end|>")
                "assistant" -> sb.appendLine("<|im_start|>assistant\n${msg.content}<|im_end|>")
            }
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    // ── Embeddings ──────────────────────────────────────────────────────

    /**
     * Generate an embedding vector for semantic search / memory retrieval.
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (!isReady) { Logger.w("Engine not ready for embedding", "AI"); return@withContext null }
        backend.embed(text)
    }

    // ── Context ─────────────────────────────────────────────────────────

    /**
     * Reset the model's context window (clears KV cache).
     */
    fun resetContext() {
        backend.resetContext()
    }

    /**
     * Estimate token count for a text string.
     */
    fun estimateTokens(text: String): Int =
        backend.estimateTokenCount(text)

    // ── Control ─────────────────────────────────────────────────────────

    /**
     * Cancel any in-progress generation.
     */
    fun cancel() {
        backend.cancel()
    }

    /**
     * Get model metadata.
     */
    fun getModelMetadata(): Map<String, String> =
        backend.getMetadata()

    /**
     * Load a model by file path directly (bypass settings).
     */
    suspend fun loadModelFromPath(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext false
        lastLoadAttempt = System.currentTimeMillis()
        val config = settings.load()
        backend.loadModel(file, config.copy(activeModelPath = path))
    }

    /**
     * Check if device has enough resources to run a model.
     */
    fun canRunModel(modelParamsB: Float): Boolean {
        val caps = profiler.getCapabilities()
        val req = profiler.estimateModelRequirements(modelParamsB)
        return caps.availableRamMb >= req.ramRequiredMb * 0.8 &&
            caps.freeStorageMb >= req.storageRequiredMb
    }
}
