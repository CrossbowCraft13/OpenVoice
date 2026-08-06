package com.example.openvoice.pipeline

import android.content.Context
import com.example.openvoice.audio.AudioCaptureManager
import com.example.openvoice.audio.AudioFrameListener
import com.example.openvoice.audio.AudioState
import com.example.openvoice.intent.IntentClassifier
import com.example.openvoice.operator.OperatorRegistry
import com.example.openvoice.router.CapabilityRouter
import com.example.openvoice.router.Resolution
import com.example.openvoice.service.AssistantService
import com.example.openvoice.stt.WhisperSttEngine
import com.example.openvoice.tts.PiperTtsEngine
import com.example.openvoice.util.Logger
import com.example.openvoice.vad.SpeechState
import com.example.openvoice.vad.VadManager
import com.example.openvoice.vad.VadEvent
import com.example.openvoice.wakeword.WakeWordDetector
import com.example.openvoice.wakeword.WakeWordEvent
import com.example.openvoice.developer.DeveloperConsole
import com.example.openvoice.perception.PerceptionEngine
import com.example.openvoice.perception.ScreenContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

sealed class PipelineEvent {
    data class Transcribed(val text: String) : PipelineEvent()
    data class IntentClassified(val intent: String, val entities: Map<String, String>) : PipelineEvent()
    data class ActionExecuted(val result: String) : PipelineEvent()
    data class Error(val message: String) : PipelineEvent()
    object ListeningStarted : PipelineEvent()
    object Idle : PipelineEvent()
}

@Singleton
class VoicePipeline @Inject constructor(
    private val context: Context,
    private val audioCapture: AudioCaptureManager,
    private val vad: VadManager,
    private val wakeWord: WakeWordDetector,
    private val stt: WhisperSttEngine,
    private val tts: PiperTtsEngine,
    private val intent: IntentClassifier,
    private val ops: OperatorRegistry,
    private val router: CapabilityRouter,
    private val console: DeveloperConsole,
    private val perception: PerceptionEngine
) {

    private val _events = MutableStateFlow<PipelineEvent>(PipelineEvent.Idle)
    val events: StateFlow<PipelineEvent> = _events

    private var job: Job? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun initialize(): Boolean {
        Logger.i("Initializing pipeline...", "Pipeline")

        // Load models
        val modelDir = File(context.filesDir, "models")
        modelDir.mkdirs()

        val whisperOk = stt.initialize(File(modelDir, "ggml-base.en.bin"))
        if (!whisperOk) Logger.w("Whisper model not available", "Pipeline")

        val piperOk = tts.loadModel(File(modelDir, "en_US-lessac-medium.onnx"))
        if (!piperOk) Logger.w("Piper model not available", "Pipeline")

        Logger.i("Pipeline initialized", "Pipeline")
        return true
    }

    fun startListening() {
        job?.cancel()
        job = scope.launch { runPipeline() }
    }

    fun stopListening() {
        job?.cancel()
        audioCapture.stop()
        _events.value = PipelineEvent.Idle
    }

    private suspend fun runPipeline() = coroutineScope {
        Logger.i("Pipeline started", "Pipeline")

        // Start audio capture
        val audioOk = audioCapture.start()
        if (!audioOk) {
            _events.value = PipelineEvent.Error("Failed to start audio capture")
            return@coroutineScope
        }
        _events.value = PipelineEvent.ListeningStarted

        // Register audio listeners
        audioCapture.addListener(vad)
        val wakeWordListener = AudioFrameListener { frame -> wakeWord.processAudio(frame) }
        audioCapture.addListener(wakeWordListener)
        val audioBuffer = mutableListOf<ShortArray>()
        val sttListener = AudioFrameListener { frame -> audioBuffer.add(frame) }
        audioCapture.addListener(sttListener)

        // VAD flow
        launch {
            console.refreshModelDiagnostics()

            // Audio level monitoring
            launch {
                audioCapture.level.collect { level -> console.onAudioLevel(level) }
            }

            vad.events.collect { events ->
                for (event in events) {
                    when (event) {
                        is VadEvent.SpeechStart -> {
                            audioBuffer.clear()
                            console.onSpeechDetected(0.0f)
                            Logger.d("VAD: Speech started", "Pipeline")
                        }
                        is VadEvent.SpeechEnd -> {
                            Logger.d("VAD: Speech ended (${event.durationMs}ms)", "Pipeline")
                            console.onSilence()
                            if (event.durationMs < 200) continue
                            val concat = audioBuffer.flatMap { it.toList() }.toShortArray()
                            if (concat.isNotEmpty()) {
                                console.onSttStart()
                                val text = stt.transcribe(concat)
                                console.onSttComplete(text)
                                if (text.isNotBlank()) {
                                    Logger.i("STT: $text", "Pipeline")
                                    _events.value = PipelineEvent.Transcribed(text)

                                    val intentResult = intent.classify(text)
                                    console.onIntentClassified(intentResult)
                                    _events.value = PipelineEvent.IntentClassified(
                                        intentResult.intent, intentResult.entities)

                                    val resolution = router.resolve(intentResult)
                                    console.onCapabilitySelected(resolution)

                                    when (resolution) {
                                        is Resolution.Native -> {
                                            val opResult = ops.exec(
                                                resolution.operatorId, context, resolution.params)
                                            Logger.i("Op: ${resolution.operatorId} → ${opResult.message}", "Pipeline")
                                            console.onActionExecuted(opResult.message)
                                            _events.value = PipelineEvent.ActionExecuted(opResult.message)
                                        }
                                        is Resolution.Accessibility -> {
                                            Logger.i("A11y: ${resolution.commands}", "Pipeline")
                                            console.onActionExecuted("A11y: ${resolution.commands.firstOrNull()}")
                                            _events.value = PipelineEvent.ActionExecuted(
                                                "A11y: ${resolution.commands.firstOrNull()}")
                                        }
                                        is Resolution.LocalLm -> {
                                            Logger.i("LLM: ${resolution.prompt}", "Pipeline")
                                            _events.value = PipelineEvent.ActionExecuted(
                                                "LLM processing: ${resolution.prompt}")
                                        }
                                        is Resolution.Vision -> {
                                            Logger.i("Vision: ${resolution.prompt}", "Pipeline")
                                            _events.value = PipelineEvent.ActionExecuted(
                                                "Analyzing screen with vision...")
                                            // Trigger perception in background
                                            launch {
                                                val (screenCtx, stats) = perception.perceive()
                                                Logger.i("Perception: ${stats.sourceUsed} " +
                                                    "(${stats.totalLatencyMs}ms)", "Pipeline")
                                                _events.value = PipelineEvent.ActionExecuted(
                                                    "Vision analysis: ${screenCtx.summary.take(100)}")
                                            }
                                        }
                                        is Resolution.Planner -> {
                                            Logger.i("Planner: ${resolution.goal}", "Pipeline")
                                            _events.value = PipelineEvent.ActionExecuted(
                                                "Planning: ${resolution.goal}")
                                        }
                                        is Resolution.Unsupported -> {
                                            Logger.w("Unsupported: ${resolution.reason}", "Pipeline")
                                            console.onError(resolution.reason)
                                            _events.value = PipelineEvent.ActionExecuted(
                                                "I don't know how to do that yet.")
                                        }
                                    }
                                    audioBuffer.clear()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun release() {
        job?.cancel()
        audioCapture.stop()
        stt.release()
        tts.release()
    }
}
