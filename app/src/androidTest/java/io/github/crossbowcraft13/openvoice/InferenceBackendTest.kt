package io.github.crossbowcraft13.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.crossbowcraft13.openvoice.ai.AiConfig
import io.github.crossbowcraft13.openvoice.ai.AiSettings
import io.github.crossbowcraft13.openvoice.ai.ChatMessage
import io.github.crossbowcraft13.openvoice.ai.DeviceProfiler
import io.github.crossbowcraft13.openvoice.ai.InferenceBackend
import io.github.crossbowcraft13.openvoice.ai.InferenceEngine
import io.github.crossbowcraft13.openvoice.ai.InferenceResult
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Coverage pass 4 — InferenceEngine success paths via a fake backend.
 *
 * InferenceEngine now depends on the [InferenceBackend] interface instead of
 * the LlamaCppBridge object directly, so the engine's full lifecycle can be
 * exercised on any device — including the CI x86_64 emulator where the
 * arm64-only native library can never load. Every test in this class drives a
 * [FakeBackend] through the real engine code, proving delegation, prompt
 * formatting, battery-saver application, and result mapping.
 *
 * The production [io.github.crossbowcraft13.openvoice.ai.LlamaCppBridge] object implements
 * the same interface; its guard behavior is covered by LlamaCppBridgeCoverageTest.
 */
@RunWith(AndroidJUnit4::class)
class InferenceBackendTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: AiSettings
    private lateinit var backend: FakeBackend

    @Before
    fun setup() {
        Logger.init(true)
        settings = AiSettings(context)
        settings.save(AiConfig()) // clean slate
        backend = FakeBackend()
        // Stale file from a previously interrupted run must not affect this one.
        File(context.filesDir, "covtest-backend-model.gguf").delete()
    }

    @After
    fun cleanup() {
        settings.save(AiConfig())
        File(context.filesDir, "covtest-backend-model.gguf").delete()
    }

    private fun engine() = InferenceEngine(context, settings, DeviceProfiler(context), backend)

    private fun modelFile(): File =
        File(context.filesDir, "covtest-backend-model.gguf").apply { writeBytes(ByteArray(64)) }

    // ── Load ────────────────────────────────────────────────────────────

    @Test
    fun loadModel_success_delegatesToBackendAndFlipsReady() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))

        val engine = engine()
        assertFalse(engine.isReady)
        assertTrue(engine.loadModel())

        assertEquals(1, backend.loadCalls)
        assertTrue(engine.isReady)
    }

    @Test
    fun loadModel_emptyPath_doesNotTouchBackend() = runBlocking {
        settings.save(AiConfig(activeModelPath = ""))
        assertFalse(engine().loadModel())
        assertEquals(0, backend.loadCalls)
    }

    @Test
    fun loadModel_missingFile_doesNotTouchBackend() = runBlocking {
        settings.save(AiConfig(activeModelPath = File(context.filesDir, "no-file.gguf").absolutePath))
        assertFalse(engine().loadModel())
        assertEquals(0, backend.loadCalls)
    }

    @Test
    fun loadModel_backendFailure_propagatesFalse() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))
        backend.loadResult = false

        assertFalse(engine().loadModel())
        assertEquals(1, backend.loadCalls)
        assertFalse(engine().isReady)
    }

    @Test
    fun loadModelFromPath_success_delegatesToBackend() = runBlocking {
        val file = modelFile()
        assertTrue(engine().loadModelFromPath(file.absolutePath))
        assertEquals(1, backend.loadCalls)
        assertEquals(file.absolutePath, backend.lastConfig?.activeModelPath)
    }

    @Test
    fun loadModelFromPath_missing_returnsFalse() = runBlocking {
        assertFalse(engine().loadModelFromPath(File(context.filesDir, "no-file.gguf").absolutePath))
        assertEquals(0, backend.loadCalls)
    }

    // ── Completion ──────────────────────────────────────────────────────

    @Test
    fun complete_success_returnsBackendText() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))
        val engine = engine()
        assertTrue(engine.loadModel())
        backend.completeResult = "The answer is 42."

        assertEquals("The answer is 42.", engine.complete("what is the answer?"))
        assertEquals(1, backend.completeCalls)
        assertEquals("what is the answer?", backend.lastPrompt)
    }

    @Test
    fun complete_appliesBatterySaverConfig() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath, batterySaverMode = true, maxTokens = 512))
        val engine = engine()
        assertTrue(engine.loadModel())

        engine.complete("hi")

        val config = backend.lastConfig
        assertEquals(1, config?.threads)
        assertEquals(256, config?.maxTokens) // 512 * 0.5
        assertTrue((config?.contextLength ?: 0) <= 1024)
    }

    @Test
    fun chat_success_buildsPromptAndReturnsSuccess() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))
        val engine = engine()
        assertTrue(engine.loadModel())
        backend.completeResult = "Sure thing!"

        val result = engine.chat(
            listOf(
                ChatMessage("system", "You are helpful."),
                ChatMessage("user", "Hi there"),
                ChatMessage("assistant", "Hello!"),
                ChatMessage("user", "How are you?")
            )
        )

        assertEquals(InferenceResult.Success("Sure thing!"), result)
        val prompt = backend.lastPrompt.orEmpty()
        assertTrue(prompt.contains("<|im_start|>system\nYou are helpful.<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\nHi there<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>assistant\nHello!<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun chat_emptyResponse_returnsError() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))
        val engine = engine()
        assertTrue(engine.loadModel())
        backend.completeResult = ""

        val result = engine.chat(listOf(ChatMessage("user", "hi")))
        assertEquals(InferenceResult.Error("Empty response"), result)
    }

    // ── Streaming ───────────────────────────────────────────────────────

    @Test
    fun chatStream_success_streamsTokensInOrder() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))
        val engine = engine()
        assertTrue(engine.loadModel())
        backend.streamTokens = listOf("Once ", "upon ", "a time")

        val received = mutableListOf<String>()
        val result = engine.chatStream(listOf(ChatMessage("user", "story"))) { received.add(it) }

        assertEquals(InferenceResult.Success("Once upon a time"), result)
        assertEquals(listOf("Once ", "upon ", "a time"), received)
        assertEquals(1, backend.streamCalls)
    }

    @Test
    fun chatStream_emptyStream_returnsError() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))
        val engine = engine()
        assertTrue(engine.loadModel())
        backend.streamTokens = emptyList()

        val result = engine.chatStream(listOf(ChatMessage("user", "story"))) {}
        assertEquals(InferenceResult.Error("Empty response"), result)
    }

    // ── Embeddings ──────────────────────────────────────────────────────

    @Test
    fun embed_success_returnsVector() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))
        val engine = engine()
        assertTrue(engine.loadModel())
        backend.embedResult = floatArrayOf(0.1f, 0.2f, 0.3f)

        val vector = engine.embed("semantic meaning")
        assertEquals(3, vector?.size)
        assertEquals(0.2f, vector?.get(1) ?: -1f, 0.001f)
        assertEquals(1, backend.embedCalls)
    }

    @Test
    fun embed_backendNull_returnsNull() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))
        val engine = engine()
        assertTrue(engine.loadModel())
        backend.embedResult = null

        assertNull(engine.embed("text"))
    }

    // ── Control / metadata ──────────────────────────────────────────────

    @Test
    fun unloadModel_releasesBackend() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))
        val engine = engine()
        assertTrue(engine.loadModel())

        engine.unloadModel()
        assertEquals(1, backend.releaseCalls)
        assertFalse(engine.isReady)
    }

    @Test
    fun resetContext_delegatesToBackend() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))
        val engine = engine()
        assertTrue(engine.loadModel())

        engine.resetContext()
        assertEquals(1, backend.resetCalls)
    }

    @Test
    fun cancel_delegatesToBackend() {
        val engine = engine()
        backend.processing = true
        assertTrue(engine.isProcessing)

        engine.cancel()
        assertEquals(1, backend.cancelCalls)
        assertFalse(engine.isProcessing)
    }

    @Test
    fun getModelMetadata_delegatesToBackend() = runBlocking {
        val file = modelFile()
        settings.save(AiConfig(activeModelPath = file.absolutePath))
        val engine = engine()
        assertTrue(engine.loadModel())
        backend.metadataResult = mapOf("general.architecture" to "qwen2", "general.name" to "test")

        assertEquals(mapOf("general.architecture" to "qwen2", "general.name" to "test"), engine.getModelMetadata())
    }

    @Test
    fun estimateTokens_delegatesToBackend() {
        val engine = engine()
        assertEquals(3, engine.estimateTokens("abcdefghijkl"))
    }

    // ── State ───────────────────────────────────────────────────────────

    @Test
    fun isProcessing_reflectsBackend() {
        val engine = engine()
        backend.processing = true
        assertTrue(engine.isProcessing)
        backend.processing = false
        assertFalse(engine.isProcessing)
    }
}

/** Deterministic in-memory [InferenceBackend] that records calls and config. */
private class FakeBackend : InferenceBackend {

    var loaded = false
    var processing = false
    var loadResult = true
    var completeResult = ""
    var streamTokens = listOf<String>()
    var embedResult: FloatArray? = null
    var metadataResult = mapOf<String, String>()

    var loadCalls = 0
    var completeCalls = 0
    var streamCalls = 0
    var embedCalls = 0
    var cancelCalls = 0
    var resetCalls = 0
    var releaseCalls = 0

    var lastPrompt: String? = null
    var lastConfig: AiConfig? = null

    override fun isLoaded(): Boolean = loaded
    override fun isProcessing(): Boolean = processing

    override fun loadModel(modelFile: File, config: AiConfig): Boolean {
        loadCalls++
        lastConfig = config
        if (!loadResult) return false
        loaded = true
        return true
    }

    override fun complete(prompt: String, config: AiConfig): String {
        completeCalls++
        lastPrompt = prompt
        lastConfig = config
        return completeResult
    }

    override fun completeStream(prompt: String, config: AiConfig, onToken: (String) -> Unit): String {
        streamCalls++
        lastPrompt = prompt
        lastConfig = config
        val sb = StringBuilder()
        for (t in streamTokens) {
            onToken(t)
            sb.append(t)
        }
        return sb.toString()
    }

    override fun embed(text: String): FloatArray? {
        embedCalls++
        return embedResult
    }

    override fun cancel() {
        cancelCalls++
        processing = false
    }

    override fun getMetadata(): Map<String, String> = metadataResult

    override fun estimateTokenCount(text: String): Int = text.length / 4

    override fun resetContext() {
        resetCalls++
    }

    override fun release() {
        releaseCalls++
        loaded = false
        processing = false
    }
}
