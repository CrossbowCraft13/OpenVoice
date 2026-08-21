package io.github.crossbowcraft13.openvoice

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.crossbowcraft13.openvoice.ai.AiConfig
import io.github.crossbowcraft13.openvoice.ai.LlamaCppBridge
import io.github.crossbowcraft13.openvoice.util.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real-inference smoke test for the llama.cpp native integration.
 *
 * Debug builds also target x86_64 (see abiFilters in app/build.gradle.kts), so
 * the emulator loads the REAL libllama_bridge.so and this test drives actual
 * llama.cpp inference end to end. The tiny GGUF is NOT checked in; provide one
 * via the modelPath instrumentation argument or by staging it into the app's
 * internal files/smoke dir (run-as; internal storage avoids the Android 11+
 * FUSE layer that hides shell-pushed files in external Android/data):
 *
 *   adb push stories15M.gguf /data/local/tmp/
 *   adb shell "run-as io.github.crossbowcraft13.openvoice.debug sh -c \
 *     'mkdir -p files/smoke && cat /data/local/tmp/stories15M.gguf > files/smoke/stories15M.gguf'"
 *   adb shell am instrument -w -e class io.github.crossbowcraft13.openvoice.NativeSmokeTest \
 *       -e modelPath /data/user/0/io.github.crossbowcraft13.openvoice.debug/files/smoke/stories15M.gguf \
 *       io.github.crossbowcraft13.openvoice.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The scan deliberately looks in files/smoke — NOT files/models — because
 * ModelManagerCoverageTest enumerates that dir with exact-count assertions;
 * keeping the two apart makes both hermetic. Without a model present the test
 * is skipped (CI stays green); with one it proves loadModel → metadata → token
 * count → sync/streaming generation → reset/cancel → release all execute in
 * real native code.
 */
@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun findTinyModel(): File? {
        val smokeDir = File(context.filesDir, "smoke")
        Logger.i("NativeSmokeTest: pkg=${context.packageName} smokeDir=$smokeDir", "AI")
        // Only pick llama GGUF files — the smoke dir is shared with
        // WhisperSmokeTest (which stages ggml-*.bin models), and feeding a
        // whisper model to llama_load_model would fail.
        return smokeDir.listFiles()?.firstOrNull {
            it.isFile && it.length() > 0 && it.name.endsWith(".gguf")
        }
    }

    @Test
    fun realNativeInference_roundTrip() {
        // Prefer an explicit path from instrumentation args (deterministic in
        // adb harnesses); fall back to scanning the dedicated smoke dir.
        val explicit = InstrumentationRegistry.getArguments().getString("modelPath")
        Logger.i(
            "NativeSmokeTest: pkg=${context.packageName} uid=${Process.myUid()} " +
                "filesDir=${context.filesDir}",
            "AI"
        )
        if (explicit != null) {
            val f = File(explicit)
            Logger.i(
                "NativeSmokeTest: explicit exists=${f.exists()} isFile=${f.isFile} " +
                    "canRead=${f.canRead()} len=${f.length()}",
                "AI"
            )
        }
        val model = explicit?.let { File(it) } ?: findTinyModel()
        assumeTrue(
            "no GGUF available; skipping real-inference smoke (pass -e modelPath or stage into files/smoke)",
            model != null && model.isFile && model.length() > 0
        )
        assertTrue("native library must load on this ABI", LlamaCppBridge.isAvailable())

        // TinyStories models train on short contexts; 256 is plenty.
        val config = AiConfig(
            contextLength = 256,
            maxTokens = 32,
            temperature = 0.8f,
            topK = 40,
            topP = 0.9f,
            repeatPenalty = 1.1f,
            threads = 2
        )

        Logger.i("NativeSmokeTest: loading ${model!!.absolutePath}", "AI")
        assertTrue("loadModel must succeed on a real GGUF", LlamaCppBridge.loadModel(model, config))
        try {
            assertTrue("isLoaded after load", LlamaCppBridge.isLoaded())
            assertTrue("metadata must enumerate GGUF keys", LlamaCppBridge.getMetadata().isNotEmpty())
            assertTrue("token count must be > 0", LlamaCppBridge.estimateTokenCount("Hello world") > 0)

            val out = LlamaCppBridge.complete("Once upon a time", config)
            assertTrue("sync completion must produce tokens, got: '${out.take(80)}'", out.isNotBlank())

            val tokens = mutableListOf<String>()
            val full = LlamaCppBridge.completeStream("Once upon a time", config) { tokens.add(it) }
            assertTrue("stream completion must produce tokens, got: '${full.take(80)}'", full.isNotBlank())
            assertTrue("streaming callback must fire per token", tokens.isNotEmpty())
            Logger.i("NativeSmokeTest: generated '${full.take(120)}'", "AI")

            LlamaCppBridge.resetContext() // must not crash
            LlamaCppBridge.cancel()       // must not crash
            assertEquals(0, LlamaCppBridge.estimateTokenCount(""))
        } finally {
            LlamaCppBridge.release()
        }
        assertFalse("isLoaded after release", LlamaCppBridge.isLoaded())
    }
}
