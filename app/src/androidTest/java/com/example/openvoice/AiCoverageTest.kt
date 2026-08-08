package com.example.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.ai.AiConfig
import com.example.openvoice.ai.AiSettings
import com.example.openvoice.ai.BenchmarkResult
import com.example.openvoice.ai.BenchmarkRunner
import com.example.openvoice.ai.ChatMessage
import com.example.openvoice.ai.DeviceProfiler
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.ai.InferenceResult
import com.example.openvoice.ai.LlamaCppBridge
import com.example.openvoice.ai.ModelManager
import com.example.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Coverage pass 3b — ai package.
 *
 * Exercises LlamaCppBridge guard branches, InferenceEngine model-lifecycle
 * guards, ModelManager file/metadata/settings logic, and BenchmarkRunner
 * without a real model.
 *
 * NOTE: these tests only exercise the "no model loaded" guards, which hold on
 * every ABI: debug builds also ship x86_64 (so the emulator DOES load the real
 * llama.cpp library), but modelPtr stays 0 without a GGUF model file, so every
 * guard below behaves identically. Real inference is covered by NativeSmokeTest.
 */
@RunWith(AndroidJUnit4::class)
class LlamaCppBridgeCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    @Test
    fun initialState_isUnloaded() {
        assertFalse(LlamaCppBridge.isLoaded())
        assertFalse(LlamaCppBridge.isProcessing())
    }

    @Test
    fun loadModel_returnsFalseWithoutUsableNative() {
        // Either the native lib is missing or the file is missing — both must
        // fail gracefully with false.
        val missing = File(context.filesDir, "no-such-model.gguf")
        assertFalse(LlamaCppBridge.loadModel(missing, AiConfig(threads = 2, contextLength = 512)))
    }

    @Test
    fun complete_returnsEmptyWhenNotLoaded() {
        assertEquals("", LlamaCppBridge.complete("hello", AiConfig()))
    }

    @Test
    fun completeStream_returnsEmptyWhenNotLoaded() {
        val tokens = mutableListOf<String>()
        val full = LlamaCppBridge.completeStream("hello", AiConfig()) { tokens.add(it) }
        assertEquals("", full)
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun embed_returnsNullWhenNotLoaded() {
        assertNull(LlamaCppBridge.embed("text"))
    }

    @Test
    fun getMetadata_returnsEmptyWhenNotLoaded() {
        assertTrue(LlamaCppBridge.getMetadata().isEmpty())
    }

    @Test
    fun estimateTokenCount_fallsBackWhenNotLoaded() {
        assertEquals(0, LlamaCppBridge.estimateTokenCount(""))
        // Fallback heuristic: length / 4
        assertEquals(2, LlamaCppBridge.estimateTokenCount("abcdefgh"))
    }

    @Test
    fun controlOperations_areSafeNoops() {
        LlamaCppBridge.cancel()
        LlamaCppBridge.resetContext()
        LlamaCppBridge.release()
        assertFalse(LlamaCppBridge.isLoaded())
    }
}

// ─────────────────────────────────────────────────────────────────────
//  InferenceEngine — lifecycle guards without a model
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class InferenceEngineCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: AiSettings

    @Before
    fun setup() {
        Logger.init(true)
        settings = AiSettings(context)
        settings.save(AiConfig()) // reset to a clean slate
    }

    private fun engine() = InferenceEngine(context, settings, DeviceProfiler(context))

    @Test
    fun loadModel_emptyPath_returnsFalse() = runBlocking {
        settings.save(AiConfig(activeModelPath = ""))
        assertFalse(engine().loadModel())
    }

    @Test
    fun loadModel_missingFile_returnsFalse() = runBlocking {
        settings.save(AiConfig(activeModelPath = File(context.filesDir, "nope.gguf").absolutePath))
        assertFalse(engine().loadModel())
    }

    @Test
    fun loadModelFromPath_missingFile_returnsFalse() = runBlocking {
        assertFalse(engine().loadModelFromPath(File(context.filesDir, "nope2.gguf").absolutePath))
    }

    @Test
    fun complete_notReady_returnsEmpty() = runBlocking {
        assertEquals("", engine().complete("prompt"))
    }

    @Test
    fun chat_notLoaded_returnsModelNotLoaded() = runBlocking {
        val result = engine().chat(listOf(ChatMessage("user", "hi")))
        assertEquals(InferenceResult.ModelNotLoaded, result)
    }

    @Test
    fun chatStream_notLoaded_returnsModelNotLoaded() = runBlocking {
        val tokens = mutableListOf<String>()
        val result = engine().chatStream(listOf(ChatMessage("user", "hi"))) { tokens.add(it) }
        assertEquals(InferenceResult.ModelNotLoaded, result)
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun embed_notReady_returnsNull() = runBlocking {
        assertNull(engine().embed("text"))
    }

    @Test
    fun control_operations_areSafe() {
        val e = engine()
        e.resetContext()
        e.cancel()
        e.unloadModel()
        assertTrue(e.getModelMetadata().isEmpty())
    }

    @Test
    fun estimateTokens_returnsPositive() {
        val count = engine().estimateTokens("hello world this is a sentence")
        assertTrue(count > 0)
    }

    @Test
    fun canRunModel_recomputesResourceGate() {
        // Deterministic: recompute the exact gate canRunModel applies instead of
        // assuming this device has enough RAM/storage headroom (which could flake
        // on a constrained CI emulator).
        val profiler = DeviceProfiler(context)
        val e = InferenceEngine(context, settings, profiler)
        val caps = profiler.getCapabilities()
        val req = profiler.estimateModelRequirements(0.1f)
        val expected = caps.availableRamMb >= req.ramRequiredMb * 0.8f &&
            caps.freeStorageMb >= req.storageRequiredMb
        assertEquals(expected, e.canRunModel(0.1f))
    }
}

// ─────────────────────────────────────────────────────────────────────
//  ModelManager — installed models, activation, deletion, download guards
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class ModelManagerCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val modelDir: File get() = File(context.filesDir, "models").also { it.mkdirs() }
    private lateinit var settings: AiSettings

    @Before
    fun setup() {
        Logger.init(true)
        settings = AiSettings(context)
        settings.save(AiConfig())
    }

    @After
    fun cleanup() {
        modelDir.listFiles()?.filter { it.name.startsWith("covtest") }?.forEach { it.delete() }
        settings.save(AiConfig())
    }

    private fun manager() = ModelManager(context, DeviceProfiler(context), settings)

    private fun writeFakeGguf(name: String): File {
        // Minimal GGUF-ish header: magic + version (int LE) + tensor count (long LE)
        val header = ByteArray(24)
        byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
            .copyInto(header, 0)
        header[4] = 1; header[5] = 0; header[6] = 0; header[7] = 0          // version = 1
        header[8] = 1; header[9] = 0; header[10] = 0; header[11] = 0        // tensor count = 1 (LE long)
        val file = File(modelDir, name)
        FileOutputStream(file).use { it.write(header) }
        return file
    }

    @Test
    fun getInstalledModels_detectsFakeGgufWithParams() = runBlocking {
        writeFakeGguf("covtest-qwen2-0_5b-instruct-q4_k_m.gguf")
        val models = manager().getInstalledModels()
        assertEquals(1, models.size)
        val m = models[0]
        assertTrue(m.fileName.contains("covtest"))
        assertTrue(m.sha256.isNotEmpty())           // checksum computed from bytes
        assertEquals(0.5f, m.paramsB, 0.01f)        // "0_5b" in filename
        assertEquals(0, m.contextSize)
        assertFalse(m.isActive)
    }

    @Test
    fun getInstalledModels_ignoresNonModelFiles() = runBlocking {
        File(modelDir, "covtest-notes.txt").writeText("not a model")
        val models = manager().getInstalledModels()
        assertTrue(models.isEmpty())
    }

    @Test
    fun getInstalledModels_nonGgufMagic_returnsEmptyMetadata() = runBlocking {
        File(modelDir, "covtest-fake.bin").writeBytes(ByteArray(64) { it.toByte() })
        val models = manager().getInstalledModels()
        assertEquals(1, models.size)
        assertEquals("", models[0].architecture) // metadata extraction failed gracefully
    }

    @Test
    fun setActiveModel_missingFile_returnsFalse() = runBlocking {
        assertFalse(manager().setActiveModel(File(modelDir, "covtest-missing.gguf").absolutePath))
        assertNull(manager().getActiveModel())
    }

    @Test
    fun setActiveModel_existingFile_updatesSettings() = runBlocking {
        val file = writeFakeGguf("covtest-active.gguf")
        assertTrue(manager().setActiveModel(file.absolutePath))
        assertEquals(file.absolutePath, manager().getActiveModel())
    }

    @Test
    fun deleteModel_missingFile_returnsFalse() = runBlocking {
        assertFalse(manager().deleteModel(File(modelDir, "covtest-ghost.gguf").absolutePath))
    }

    @Test
    fun deleteModel_removesFile() = runBlocking {
        val file = writeFakeGguf("covtest-delete.gguf")
        assertTrue(file.exists())
        assertTrue(manager().deleteModel(file.absolutePath))
        assertFalse(file.exists())
    }

    @Test
    fun deleteModel_activeModel_resetsSettings() = runBlocking {
        val mgr = manager()
        val file = writeFakeGguf("covtest-active-del.gguf")
        mgr.setActiveModel(file.absolutePath)
        assertTrue(mgr.deleteModel(file.absolutePath))
        assertNull(mgr.getActiveModel())
    }

    @Test
    fun downloadModel_alreadyExists_returnsTrue() = runBlocking {
        // Custom name (covtest-prefixed so @After cleanup removes it)
        val rec = com.example.openvoice.ai.ModelManager.RecommendedModel(
            name = "covtest-existing",
            url = "http://",
            sha256 = "",
            paramsB = 0.1f,
            ramRequiredMb = 128,
            fileSizeMb = 1
        )
        File(modelDir, rec.name + ".gguf").writeText("already here")
        assertTrue(manager().downloadModel(rec))
    }

    @Test
    fun downloadModel_invalidUrl_returnsFalse() = runBlocking {
        val rec = com.example.openvoice.ai.ModelManager.RecommendedModel(
            name = "covtest-bad-url",
            url = "http://",           // malformed → throws immediately, no network
            sha256 = "",
            paramsB = 0.1f,
            ramRequiredMb = 128,
            fileSizeMb = 1
        )
        assertFalse(manager().downloadModel(rec))
    }

    @Test
    fun getStorageUsage_measuresFiles() = runBlocking {
        writeFakeGguf("covtest-size.gguf")
        val (usedMb, freeMb) = manager().getStorageUsage()
        assertTrue(usedMb >= 0)
        assertTrue(freeMb > 0)
    }

    @Test
    fun getModelRequirements_delegatesToProfiler() {
        val req = manager().getModelRequirements(1.0f)
        assertTrue(req.ramRequiredMb > 0)
        assertTrue(req.storageRequiredMb > 0)
    }

    @Test
    fun recommendedModels_areValid() {
        val models = manager().recommendedModels
        assertTrue(models.isNotEmpty())
        assertTrue(models.all { it.name.isNotBlank() && it.url.isNotBlank() && it.paramsB > 0f })
    }
}

// ─────────────────────────────────────────────────────────────────────
//  BenchmarkRunner — runs without a model and still returns results
// ─────────────────────────────────────────────────────────────────────
@RunWith(AndroidJUnit4::class)
class BenchmarkRunnerCoverageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun runner(): BenchmarkRunner {
        val settings = AiSettings(context).also { it.save(AiConfig()) }
        val profiler = DeviceProfiler(context)
        val engine = InferenceEngine(context, settings, profiler)
        return BenchmarkRunner(engine, profiler, settings)
    }

    @Test
    fun runAll_withoutModel_returnsFourResults() = runBlocking {
        val results = runner().runAll()
        assertEquals(4, results.size) // one per test prompt
        for (r in results) {
            assertTrue(r.coldStartMs >= 0)   // -1 clamped to 0 when load fails
            assertTrue(r.warmStartMs >= 0)
            assertTrue(r.tokensPerSecond >= 0f)
            assertTrue(r.totalTokens >= 0)
            assertTrue(r.promptProcessingMs >= 0)
            assertEquals("unknown", r.modelName)
        }
    }

    @Test
    fun measureThroughput_withoutModel_returnsResult() = runBlocking {
        val result = runner().measureThroughput("tell me a story")
        assertNotNull(result)
        assertTrue(result.tokensPerSecond >= 0f)
        assertTrue(result.promptProcessingMs >= 1)
        assertTrue(result.totalTokens >= 0)
    }

    @Test
    fun benchmarkResult_defaults() {
        val r = BenchmarkResult()
        assertEquals("", r.modelName)
        assertEquals(0, r.firstTokenMs)
        assertEquals(0f, r.tokensPerSecond, 0.01f)
        assertNotEquals(r, BenchmarkResult(coldStartMs = 1))
    }
}
