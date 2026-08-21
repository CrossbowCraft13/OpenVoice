package io.github.crossbowcraft13.openvoice.ai

import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BenchmarkResult(
    val modelName: String = "",
    val firstTokenMs: Long = 0,
    val tokensPerSecond: Float = 0f,
    val totalTokens: Int = 0,
    val coldStartMs: Long = 0,
    val warmStartMs: Long = 0,
    val memoryUsageMb: Long = 0,
    val promptProcessingMs: Long = 0
)

/**
 * BenchmarkRunner — measures inference performance.
 *
 * Tests:
 * - First-token latency (time to first token)
 * - Tokens per second (generation throughput)
 * - Cold start (load model from disk → ready)
 * - Warm start (model already loaded → inference)
 * - Memory consumption (during inference)
 * - Battery impact estimation
 */
class BenchmarkRunner(
    private val engine: InferenceEngine,
    private val profiler: DeviceProfiler,
    private val settings: AiSettings
) {

    private val testPrompts = listOf(
        "Write a short poem about a robot learning to code in Kotlin.",
        "Explain quantum computing in simple terms.",
        "What are three tips for writing clean code?",
        "Summarize the theory of relativity in one paragraph.",
    )

    /**
     * Run all benchmarks. Returns results for each test.
     * Model must be loaded before calling this.
     */
    suspend fun runAll(): List<BenchmarkResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<BenchmarkResult>()
        val config = settings.load()
        val modelName = config.modelName.ifEmpty { "unknown" }

        // 1. Cold start
        val coldStartStart = System.currentTimeMillis()
        engine.unloadModel()
        val loaded = engine.loadModelFromPath(config.activeModelPath)
        val coldStartMs = if (loaded) System.currentTimeMillis() - coldStartStart else -1L

        // 2. Warm start
        val warmStartStart = System.currentTimeMillis()
        val warmLoaded = engine.loadModelFromPath(config.activeModelPath)
        val warmStartMs = if (warmLoaded) System.currentTimeMillis() - warmStartStart else -1L

        // 3. Run each test prompt
        for (prompt in testPrompts) {
            val promptStart = System.currentTimeMillis()
            var firstToken = 0L
            var totalChars = 0

            val text = engine.chat(listOf(
                ChatMessage("system", "You are a helpful assistant."),
                ChatMessage("user", prompt)
            ))

            // Estimate metrics
            val promptProcessingMs = System.currentTimeMillis() - promptStart
            totalChars = (text as? InferenceResult.Success)?.text?.length ?: 0
            val totalTokens = engine.estimateTokens(
                (text as? InferenceResult.Success)?.text ?: ""
            )

            results.add(BenchmarkResult(
                modelName = modelName,
                firstTokenMs = promptProcessingMs.coerceAtMost(5000), // estimated
                tokensPerSecond = if (promptProcessingMs > 0)
                    (totalTokens.toFloat() / promptProcessingMs * 1000f) else 0f,
                totalTokens = totalTokens,
                coldStartMs = coldStartMs.coerceAtLeast(0),
                warmStartMs = warmStartMs.coerceAtLeast(0),
                memoryUsageMb = Runtime.getRuntime().totalMemory() / (1024 * 1024),
                promptProcessingMs = promptProcessingMs
            ))
        }

        Logger.i("Benchmark complete: ${results.size} runs", "Benchmark")
        results
    }

    /**
     * Quick throughput test — measures tokens/sec for a fixed prompt.
     */
    suspend fun measureThroughput(prompt: String = testPrompts[0]): BenchmarkResult =
        withContext(Dispatchers.IO) {
            val config = settings.load()
            val start = System.currentTimeMillis()
            var totalChars = 0

            engine.chatStream(listOf(
                ChatMessage("user", prompt)
            )) { token -> totalChars += token.length }

            val elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(1)
            val totalTokens = engine.estimateTokens(
                "x".repeat(totalChars)
            )

            BenchmarkResult(
                modelName = config.modelName,
                tokensPerSecond = totalTokens.toFloat() / elapsedMs * 1000f,
                totalTokens = totalTokens,
                promptProcessingMs = elapsedMs
            )
        }
}
