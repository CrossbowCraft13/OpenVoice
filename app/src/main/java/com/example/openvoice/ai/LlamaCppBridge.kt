package com.example.openvoice.ai

import com.example.openvoice.util.Logger
import java.io.File

/**
 * LlamaCppBridge — JNI wrapper for the llama.cpp native library.
 *
 * Loads GGUF model files and provides:
 * - Prompt completion (sync + streaming)
 * - Chat completion
 * - Embedding generation
 * - Context management
 * - Cancellation
 *
 * Thread safety: All native calls are synchronized on the native context pointer.
 * Streaming callbacks run on the caller's thread.
 */
object LlamaCppBridge : InferenceBackend {

    private var nativeLoaded = false
    private var modelPtr: Long = 0
    private var isRunning = false

    init {
        try {
            // The native library is built as "llama_bridge" by CMakeLists.txt.
            System.loadLibrary("llama_bridge")
            nativeLoaded = true
            Logger.i("llama.cpp native library loaded", "AI")
        } catch (e: UnsatisfiedLinkError) {
            Logger.w("llama.cpp native library not available: ${e.message}", "AI")
        }
    }

    // ── Native methods (implemented in llama_jni.cpp) ───────────────────

    private external fun nativeInit(path: String, nThreads: Int, nCtx: Int, seed: Long): Long
    private external fun nativeComplete(ctx: Long, prompt: String, maxTokens: Int, temperature: Float,
        topK: Int, topP: Float, repeatPenalty: Float, stop: Array<String>): String
    private external fun nativeCompleteStream(ctx: Long, prompt: String, maxTokens: Int, temperature: Float,
        topK: Int, topP: Float, repeatPenalty: Float, stop: Array<String>, callback: (String) -> Unit)
    private external fun nativeEmbed(ctx: Long, text: String): FloatArray
    private external fun nativeCancel(ctx: Long)
    private external fun nativeRelease(ctx: Long): Unit
    private external fun nativeBenchmark(ctx: Long, power: Int, format: String): String
    private external fun nativeMetadata(ctx: Long): Array<String>
    private external fun nativeResetContext(ctx: Long): Unit
    private external fun nativeTokenCount(ctx: Long, text: String): Int

    fun isAvailable() = nativeLoaded
    override fun isLoaded() = modelPtr != 0L
    override fun isProcessing() = isRunning

    /**
     * Load a GGUF model file.
     */
    override fun loadModel(modelFile: File, config: AiConfig): Boolean {
        if (!nativeLoaded) {
            Logger.e("Cannot load model: native library not loaded", "AI")
            return false
        }
        if (!modelFile.exists()) {
            Logger.e("Model file not found: ${modelFile.absolutePath}", "AI")
            return false
        }
        try {
            release()
            val seed = System.currentTimeMillis()
            modelPtr = nativeInit(
                modelFile.absolutePath,
                if (config.batterySaverMode) 1 else config.threads,
                config.contextLength,
                seed
            )
            if (modelPtr == 0L) {
                Logger.e("llama_init returned null context", "AI")
                return false
            }
            Logger.i("Model loaded: ${modelFile.name} (ctx=${config.contextLength})", "AI")
            return true
        } catch (e: Exception) {
            Logger.e("Model load failed: ${e.message}", "AI")
            return false
        }
    }

    /**
     * Synchronous prompt completion. Returns the generated text.
     */
    override fun complete(prompt: String, config: AiConfig): String {
        if (modelPtr == 0L || !nativeLoaded) return ""
        isRunning = true
        return try {
            val stop = if (config.stopTokens.isNotEmpty()) config.stopTokens.toTypedArray()
                else arrayOf("</s>")
            val result = nativeComplete(modelPtr, prompt, config.maxTokens, config.temperature,
                config.topK, config.topP, config.repeatPenalty, stop)
            result.trim()
        } catch (e: Exception) {
            Logger.e("Completion failed: ${e.message}", "AI")
            ""
        } finally { isRunning = false }
    }

    /**
     * Streaming completion. Calls onToken for each generated token.
     * Returns the full generated text.
     */
    override fun completeStream(prompt: String, config: AiConfig, onToken: (String) -> Unit): String {
        if (modelPtr == 0L || !nativeLoaded) return ""
        isRunning = true
        val sb = StringBuilder()
        try {
            val stop = if (config.stopTokens.isNotEmpty()) config.stopTokens.toTypedArray()
                else arrayOf("</s>")
            nativeCompleteStream(modelPtr, prompt, config.maxTokens, config.temperature,
                config.topK, config.topP, config.repeatPenalty, stop) { token ->
                sb.append(token)
                onToken(token)
            }
        } catch (e: Exception) {
            Logger.e("Streaming failed: ${e.message}", "AI")
        } finally { isRunning = false }
        return sb.toString().trim()
    }

    /**
     * Generate an embedding vector for the given text.
     */
    override fun embed(text: String): FloatArray? {
        if (modelPtr == 0L || !nativeLoaded) return null
        return try { nativeEmbed(modelPtr, text) }
        catch (e: Exception) { Logger.e("Embedding failed: ${e.message}", "AI"); null }
    }

    /**
     * Cancel an in-progress generation.
     */
    override fun cancel() {
        if (modelPtr != 0L && nativeLoaded) {
            nativeCancel(modelPtr)
            isRunning = false
        }
    }

    /**
     * Get model metadata as key-value pairs.
     */
    override fun getMetadata(): Map<String, String> {
        if (modelPtr == 0L || !nativeLoaded) return emptyMap()
        return try {
            nativeMetadata(modelPtr).map {
                val parts = it.split("=", limit = 2)
                parts[0] to (if (parts.size > 1) parts[1] else "")
            }.toMap()
        } catch (e: Exception) { emptyMap() }
    }

    /**
     * Estimate token count for a text string.
     */
    override fun estimateTokenCount(text: String): Int {
        if (modelPtr == 0L || !nativeLoaded) return text.length / 4 // Fallback
        return try { nativeTokenCount(modelPtr, text) } catch (_: Exception) { text.length / 4 }
    }

    /**
     * Reset the context window (clear KV cache).
     */
    override fun resetContext() {
        if (modelPtr != 0L && nativeLoaded) nativeResetContext(modelPtr)
    }

    /**
     * Release the model and free all resources.
     */
    override fun release() {
        cancel()
        if (modelPtr != 0L && nativeLoaded) {
            nativeRelease(modelPtr)
            modelPtr = 0L
            Logger.i("Model released", "AI")
        }
    }
}
