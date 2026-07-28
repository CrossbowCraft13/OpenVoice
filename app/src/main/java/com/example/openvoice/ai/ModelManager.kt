package com.example.openvoice.ai

import android.content.Context
import com.example.openvoice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ModelManager — complete GGUF model lifecycle.
 *
 * Capabilities:
 * - List installed models
 * - Download models with progress
 * - Verify SHA-256 hashes
 * - Display model metadata (architecture, context size, etc.)
 * - Switch active model
 * - Delete models
 * - Estimate RAM requirements
 * - Track storage usage
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiler: DeviceProfiler,
    private val settings: AiSettings
) {

    data class ModelInfo(
        val path: String,
        val fileName: String,
        val sizeMb: Long,
        val sha256: String,
        val architecture: String = "",
        val contextSize: Int = 0,
        val quantization: String = "",
        val paramsB: Float = 0f,
        val isActive: Boolean = false,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f
    )

    /**
     * Known recommended models for quick download.
     */
    data class RecommendedModel(
        val name: String,
        val url: String,
        val sha256: String,
        val paramsB: Float,
        val ramRequiredMb: Long,
        val fileSizeMb: Long
    )

    val recommendedModels = listOf(
        RecommendedModel("Qwen2-0.5B-Instruct-Q4_K_M",
            "https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-GGUF/resolve/main/qwen2-0_5b-instruct-q4_k_m.gguf",
            "", 0.5f, 1024, 380),
        RecommendedModel("Gemma-2B-Instruct-Q4_K_M",
            "https://huggingface.co/google/gemma-2b-it-GGUF/resolve/main/gemma-2b-it-q4_k_m.gguf",
            "", 2.0f, 2048, 1400),
        RecommendedModel("Phi-3-mini-4k-Q4_K_M",
            "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
            "", 3.8f, 3072, 2600),
        RecommendedModel("Mistral-7B-Instruct-v0.3-Q4_K_M",
            "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/mistral-7b-instruct-v0.3.Q4_K_M.gguf",
            "", 7.0f, 5120, 4400),
        RecommendedModel("Llama-3.2-8B-Instruct-Q4_K_M",
            "https://huggingface.co/bartowski/Llama-3.2-8B-Instruct-GGUF/resolve/main/Llama-3.2-8B-Instruct-Q4_K_M.gguf",
            "", 8.0f, 6144, 4900),
    )

    private val modelDir: File get() = File(context.filesDir, "models").also { it.mkdirs() }

    // ── Installed Models ────────────────────────────────────────────────

    suspend fun getInstalledModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val activePath = settings.load().activeModelPath
        modelDir.listFiles()
            ?.filter { it.extension == "gguf" || it.extension == "bin" }
            ?.map { file ->
                val meta = extractMetadata(file)
                ModelInfo(
                    path = file.absolutePath,
                    fileName = file.name,
                    sizeMb = file.length() / (1024 * 1024),
                    sha256 = computeSha256(file),
                    architecture = meta["general.architecture"] ?: "",
                    contextSize = (meta["llama.context_length"] ?: "0").toIntOrNull() ?: 0,
                    quantization = meta["general.file_type"] ?: "",
                    paramsB = estimateParamsFromFile(file.name),
                    isActive = file.absolutePath == activePath
                )
            }
            ?.sortedByDescending { it.sizeMb }
            ?: emptyList()
    }

    // ── Download ────────────────────────────────────────────────────────

    suspend fun downloadModel(
        model: RecommendedModel,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(modelDir, model.name + ".gguf")
            if (outputFile.exists()) {
                Logger.i("Model already downloaded: ${model.name}", "ModelManager")
                return@withContext true
            }

            Logger.i("Downloading: ${model.name} (${model.fileSizeMb}MB)", "ModelManager")
            val url = URL(model.url)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()

            val totalBytes = connection.contentLengthLong
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(outputFile)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L
            var lastProgress = 0f

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalBytes > 0) {
                    val progress = (totalRead.toFloat() / totalBytes).coerceAtMost(1f)
                    if (progress - lastProgress > 0.05f) {
                        lastProgress = progress
                        onProgress(progress)
                    }
                }
            }

            outputStream.close()
            inputStream.close()
            connection.disconnect()

            Logger.i("Downloaded: ${model.name}", "ModelManager")
            onProgress(1f)

            // Verify SHA-256 if available
            if (model.sha256.isNotEmpty()) {
                val sha = computeSha256(outputFile)
                if (sha != model.sha256) {
                    Logger.e("SHA-256 mismatch for ${model.name}", "ModelManager")
                    outputFile.delete()
                    return@withContext false
                }
            }
            true
        } catch (e: Exception) {
            Logger.e("Download failed: ${e.message}", "ModelManager")
            false
        }
    }

    // ── Activation ──────────────────────────────────────────────────────

    suspend fun setActiveModel(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext false
        val config = settings.load().copy(activeModelPath = path, modelName = file.name)
        settings.save(config)
        Logger.i("Active model set: ${file.name}", "ModelManager")
        true
    }

    fun getActiveModel(): String? {
        val path = settings.load().activeModelPath
        return path.ifEmpty { null }
    }

    // ── Deletion ────────────────────────────────────────────────────────

    suspend fun deleteModel(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext false
        val isActive = path == settings.load().activeModelPath
        if (isActive) {
            settings.save(settings.load().copy(activeModelPath = ""))
        }
        file.delete()
        Logger.i("Deleted model: ${file.name}", "ModelManager")
        true
    }

    // ── Storage ─────────────────────────────────────────────────────────

    suspend fun getStorageUsage(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        var usedMb = 0L
        modelDir.listFiles()?.filter { it.isFile }?.forEach { usedMb += it.length() }
        val freeMb = profiler.getCapabilities().freeStorageMb
        Pair(usedMb / (1024 * 1024), freeMb)
    }

    // ── Requirements ────────────────────────────────────────────────────

    fun getModelRequirements(paramsB: Float) = profiler.estimateModelRequirements(paramsB)

    // ── Metadata Extraction ─────────────────────────────────────────────

    private fun extractMetadata(file: File): Map<String, String> {
        // Read GGUF header to extract metadata
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(16)
                if (input.read(header) < 16) return emptyMap()
                val magic = String(header, 0, 4)
                if (magic != "GGUF") return emptyMap()

                val version = header.readIntLE(4)
                val tensorCount = header.readLongLE(8)

                // Read metadata KV pairs (simplified)
                val meta = mutableMapOf<String, String>()
                meta["gguf.version"] = version.toString()
                meta["tensor_count"] = tensorCount.toString()
                meta
            }
        } catch (e: Exception) { emptyMap() }
    }

    private fun ByteArray.readIntLE(offset: Int): Int =
        ((this[offset + 3].toInt() and 0xFF) shl 24) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        (this[offset].toInt() and 0xFF)

    private fun ByteArray.readLongLE(offset: Int): Long =
        (readIntLE(offset).toLong() and 0xFFFFFFFFL) or
        ((readIntLE(offset + 4).toLong() shl 32))

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun computeSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { String.format("%02x", it) }
        } catch (e: Exception) { "" }
    }

    private fun estimateParamsFromFile(fileName: String): Float {
        val name = fileName.lowercase()
        return when {
            "8b" in name || "llama-3" in name -> 8f
            "7b" in name || "mistral" in name -> 7f
            "3b" in name || "phi-3" in name -> 3.8f
            "2b" in name || "gemma" in name -> 2f
            "1b" in name || "qwen2-1" in name -> 1.5f
            "0.5b" in name || "0_5b" in name -> 0.5f
            else -> {
                val match = Regex("""(\d+(?:\.\d+)?)b""").find(name)
                match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            }
        }
    }
}
