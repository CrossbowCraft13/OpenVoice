package com.example.openvoice.developer

import com.example.openvoice.ai.DeviceProfiler
import com.example.openvoice.ai.InferenceEngine
import com.example.openvoice.ai.ModelManager
import com.example.openvoice.ai.BenchmarkRunner
import com.example.openvoice.ai.BenchmarkResult
import com.example.openvoice.router.CapabilityRouter
import com.example.openvoice.router.Resolution
import com.example.openvoice.intent.IntentResult
import com.example.openvoice.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live diagnostics data for the Developer Console.
 * Every pipeline component pushes events here in real time.
 */
data class PipelineDiagnostics(
    val audioLevel: Float = 0f,
    val speechState: String = "silence",
    val vadConfidence: Float = 0f,
    val wakeWordDetected: String = "",
    val wakeWordConfidence: Float = 0f,
    val transcript: String = "",
    val sttLatencyMs: Long = 0,
    val intent: String = "",
    val intentConfidence: Float = 0f,
    val intentLatencyMs: Long = 0,
    val capabilitySelected: String = "",
    val actionExecuted: String = "",
    val actionLatencyMs: Long = 0,
    val totalLatencyMs: Long = 0,
    val routerDecisions: List<String> = emptyList(),
    val a11yNodeMatches: Int = 0,
    val lmTokenSpeed: Float = 0f,
    val lmMemoryMb: Long = 0,
    val modelName: String = "",
    val activeModel: String = "",
    val deviceSummary: String = "",
    // ── Perception ────────────────────────────────────────────────
    val ocrTextBlocks: Int = 0,
    val ocrLatencyMs: Long = 0,
    val visionElements: Int = 0,
    val visionLatencyMs: Long = 0,
    val perceptionSource: String = "",
    val perceptionTotalLatencyMs: Long = 0,
    val screenContextElements: Int = 0,
    val screenApp: String = "",
    val cacheHit: Boolean = false,
    val errors: List<String> = emptyList()
)

@Singleton
class DeveloperConsole @Inject constructor(
    private val profiler: DeviceProfiler,
    private val engine: InferenceEngine,
    private val modelManager: ModelManager,
    private val router: CapabilityRouter
) {

    private val _diag = MutableStateFlow(PipelineDiagnostics())
    val diagnostics: StateFlow<PipelineDiagnostics> = _diag

    private val eventLog = mutableListOf<String>()
    val recentEvents: List<String> get() = eventLog.takeLast(50)

    private val benchmarkResults = mutableListOf<BenchmarkResult>()
    val benchmarks: List<BenchmarkResult> get() = benchmarkResults.toList()

    private var pipelineStartNs = 0L
    private var lastSttStartNs = 0L
    private var lastIntentStartNs = 0L
    private var lastActionStartNs = 0L

    // ── Pipeline Event Recording ────────────────────────────────────────

    fun onAudioLevel(level: Float) {
        _diag.value = _diag.value.copy(audioLevel = level)
    }

    fun onSpeechDetected(confidence: Float) {
        pipelineStartNs = System.nanoTime()
        _diag.value = _diag.value.copy(speechState = "speech", vadConfidence = confidence)
        logEvent("🟢 Speech Detected (conf=${"%.2f".format(confidence)})")
    }

    fun onSilence() {
        _diag.value = _diag.value.copy(speechState = "silence")
    }

    fun onWakeWord(word: String, confidence: Float) {
        _diag.value = _diag.value.copy(wakeWordDetected = word, wakeWordConfidence = confidence)
        logEvent("🎙 Wake Word: '$word' (${"%.1f".format(confidence * 100)}%)")
    }

    fun onSttStart() {
        lastSttStartNs = System.nanoTime()
    }

    fun onSttComplete(transcript: String) {
        val latency = (System.nanoTime() - lastSttStartNs) / 1_000_000
        _diag.value = _diag.value.copy(transcript = transcript, sttLatencyMs = latency)
        logEvent("📝 Transcript: \"$transcript\" (${latency}ms)")
    }

    fun onIntentClassified(intent: IntentResult) {
        val latency = (System.nanoTime() - lastSttStartNs) / 1_000_000
        _diag.value = _diag.value.copy(
            intent = intent.intent,
            intentConfidence = intent.confidence,
            intentLatencyMs = latency
        )
        logEvent("🎯 Intent: ${intent.intent} (${"%.0f".format(intent.confidence * 100)}%)")
        lastIntentStartNs = System.nanoTime()
    }

    fun onCapabilitySelected(resolution: Resolution) {
        val label = when (resolution) {
            is Resolution.Native -> "Native: ${resolution.operatorId}"
            is Resolution.Accessibility -> "Accessibility"
            is Resolution.LocalLm -> "Local LLM"
            is Resolution.Vision -> "Vision"
            is Resolution.Planner -> "Planner"
            is Resolution.Unsupported -> "Unsupported"
        }
        _diag.value = _diag.value.copy(
            capabilitySelected = label,
            routerDecisions = _diag.value.routerDecisions + label
        )
        logEvent("🧠 Capability: $label")
        lastActionStartNs = System.nanoTime()
    }

    fun onActionExecuted(result: String) {
        val latency = (System.nanoTime() - lastIntentStartNs) / 1_000_000
        val totalLatency = (System.nanoTime() - pipelineStartNs) / 1_000_000
        _diag.value = _diag.value.copy(
            actionExecuted = result,
            actionLatencyMs = latency,
            totalLatencyMs = totalLatency
        )
        logEvent("📱 Action: $result (${latency}ms, total=${totalLatency}ms)")
    }

    fun onError(message: String) {
        _diag.value = _diag.value.copy(
            errors = _diag.value.errors + message
        )
        logEvent("❌ Error: $message")
    }

    // ── Perception Diagnostics ──────────────────────────────────────────

    fun onPerceptionComplete(
        source: String,
        totalLatencyMs: Long,
        ocrBlocks: Int,
        ocrLatencyMs: Long,
        visionElements: Int,
        visionLatencyMs: Long,
        screenElements: Int,
        screenApp: String,
        cacheHit: Boolean
    ) {
        _diag.value = _diag.value.copy(
            perceptionSource = source,
            perceptionTotalLatencyMs = totalLatencyMs,
            ocrTextBlocks = ocrBlocks,
            ocrLatencyMs = ocrLatencyMs,
            visionElements = visionElements,
            visionLatencyMs = visionLatencyMs,
            screenContextElements = screenElements,
            screenApp = screenApp,
            cacheHit = cacheHit
        )
        logEvent("👁 Perception: $source ($totalLatencyMs ms)" +
            if (cacheHit) " [cached]" else "" +
            " | OCR: ${ocrBlocks}blocks/${ocrLatencyMs}ms" +
            " | Vision: ${visionElements}el/${visionLatencyMs}ms")
    }

    // ── Model Diagnostics ───────────────────────────────────────────────

    fun refreshModelDiagnostics() {
        val caps = profiler.getCapabilities()
        val summary = profiler.getSummary()
        val modelName = engine.let {
            if (it.isReady) "Model loaded" else "No model loaded"
        }
        _diag.value = _diag.value.copy(
            modelName = modelName,
            activeModel = "active",
            deviceSummary = summary,
            lmMemoryMb = Runtime.getRuntime().totalMemory() / (1024 * 1024)
        )
    }

    // ── Benchmarking ────────────────────────────────────────────────────

    suspend fun runBenchmark(): List<BenchmarkResult> {
        val runner = BenchmarkRunner(engine, profiler, modelManager.getSettings())
        val results = runner.runAll()
        benchmarkResults.addAll(results)
        logEvent("Benchmark: ${results.size} runs completed")
        return results
    }

    // ── Logging ─────────────────────────────────────────────────────────

    private fun logEvent(event: String) {
        eventLog.add(event)
        if (eventLog.size > 200) eventLog.removeAt(0)
    }

    fun getEventLog(): List<String> = recentEvents

    fun clearLog() {
        eventLog.clear()
    }

    fun resetPipeline() {
        _diag.value = PipelineDiagnostics(
            deviceSummary = _diag.value.deviceSummary,
            modelName = _diag.value.modelName,
            activeModel = _diag.value.activeModel
        )
        pipelineStartNs = 0L
    }

    fun getTimelineSummary(): String {
        val d = _diag.value
        return buildString {
            appendLine("🎤 Audio: ${"%.0f".format(d.audioLevel * 100)}%")
            appendLine("🟢 VAD: ${d.speechState} (${"%.2f".format(d.vadConfidence)})")
            if (d.wakeWordDetected.isNotEmpty())
                appendLine("🎙 Wake: ${d.wakeWordDetected} (${"%.0f".format(d.wakeWordConfidence * 100)}%)")
            if (d.transcript.isNotEmpty())
                appendLine("📝 STT: \"${d.transcript}\" (${d.sttLatencyMs}ms)")
            if (d.intent.isNotEmpty())
                appendLine("🎯 Intent: ${d.intent} (${d.intentLatencyMs}ms)")
            if (d.capabilitySelected.isNotEmpty())
                appendLine("🧠 Route: ${d.capabilitySelected}")
            if (d.actionExecuted.isNotEmpty())
                appendLine("📱 Action: ${d.actionExecuted} (${d.totalLatencyMs}ms)")
            if (d.lmTokenSpeed > 0)
                appendLine("⚡ LLM: ${"%.1f".format(d.lmTokenSpeed)} tok/s")
        }.trimEnd()
    }
}
