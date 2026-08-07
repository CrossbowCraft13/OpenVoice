package com.example.openvoice.ai

import java.io.File

/**
 * InferenceBackend — the pluggable execution layer behind [InferenceEngine].
 *
 * InferenceEngine depends on this interface instead of the LlamaCppBridge
 * object directly, so:
 *  - the engine's full lifecycle (load / complete / stream / embed / metadata)
 *    can be exercised with a fake backend on any device, including CI
 *    emulators where the arm64-only native library can never load;
 *  - alternative backends (ONNX, ML Kit, remote) can be swapped in without
 *    touching any caller.
 *
 * [LlamaCppBridge] is the production implementation (JNI wrapper).
 */
interface InferenceBackend {

    /** Whether a model is currently loaded and ready for inference. */
    fun isLoaded(): Boolean

    /** Whether inference is currently running. */
    fun isProcessing(): Boolean

    /** Load a GGUF model file. */
    fun loadModel(modelFile: File, config: AiConfig): Boolean

    /**
     * Synchronous prompt completion. Returns the generated text verbatim
     * (any trimming of output is the backend's responsibility, not the
     * engine's).
     */
    fun complete(prompt: String, config: AiConfig): String

    /**
     * Streaming completion; onToken fires per generated token. Returns the
     * full generated text verbatim (no engine-side trimming).
     */
    fun completeStream(prompt: String, config: AiConfig, onToken: (String) -> Unit): String

    /** Generate an embedding vector for the given text. */
    fun embed(text: String): FloatArray?

    /** Cancel any in-progress generation. */
    fun cancel()

    /** Model metadata as key-value pairs. */
    fun getMetadata(): Map<String, String>

    /** Estimate the token count of a text string. */
    fun estimateTokenCount(text: String): Int

    /** Reset the model's context window (clear KV cache). */
    fun resetContext()

    /** Release the model and free all resources. */
    fun release()
}
