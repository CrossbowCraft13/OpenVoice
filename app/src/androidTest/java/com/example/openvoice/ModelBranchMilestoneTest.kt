package com.example.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.ai.AiSettings
import com.example.openvoice.ai.DeviceProfiler
import com.example.openvoice.ai.ModelManager
import com.example.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ModelBranchMilestoneTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val modelDir get() = File(context.filesDir, "models").also { it.mkdirs() }

    @Before
    fun setup() {
        Logger.init(true)
    }

    @After
    fun cleanup() {
        modelDir.listFiles()?.filter { it.name.startsWith("branchcov-") }?.forEach { it.delete() }
    }

    @Test
    fun installedModelNames_coverEveryParameterEstimatorBranch() = runBlocking {
        val expected = linkedMapOf(
            "branchcov-8b.gguf" to 8f,
            "branchcov-llama-3.gguf" to 8f,
            "branchcov-7b.gguf" to 7f,
            "branchcov-mistral.gguf" to 7f,
            "branchcov-3b.gguf" to 3.8f,
            "branchcov-phi-3.gguf" to 3.8f,
            "branchcov-2b.gguf" to 2f,
            "branchcov-gemma.gguf" to 2f,
            "branchcov-1b.gguf" to 1.5f,
            "branchcov-qwen2-1.gguf" to 1.5f,
            "branchcov-0.5b.gguf" to 0.5f,
            "branchcov-0_5b.gguf" to 0.5f,
            "branchcov-4.9b.gguf" to 4.9f,
            "branchcov-unknown.gguf" to 0f
        )
        expected.keys.forEach { File(modelDir, it).writeBytes(ByteArray(32)) }

        val manager = ModelManager(context, DeviceProfiler(context), AiSettings(context))
        val actual = manager.getInstalledModels()
            .filter { it.fileName.startsWith("branchcov-") }
            .associate { it.fileName to it.paramsB }
        assertEquals(expected.size, actual.size)
        expected.forEach { (name, params) -> assertEquals(params, actual[name] ?: -1f, 0.01f) }
    }
}
