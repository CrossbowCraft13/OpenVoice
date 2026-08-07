package com.example.openvoice.vad

import android.content.Context
import com.example.openvoice.audio.AudioFrameListener
import com.example.openvoice.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class SpeechState { SILENCE, SPEECH }

sealed class VadEvent {
    data class SpeechStart(val timestamp: Long) : VadEvent()
    data class SpeechEnd(val timestamp: Long, val durationMs: Long) : VadEvent()
}

@Singleton
class VadManager @Inject constructor(context: Context?) : AudioFrameListener {

    private val engine = SileroVadEngine(context)
    private val _state = MutableStateFlow(SpeechState.SILENCE)
    val state: StateFlow<SpeechState> = _state

    private val _events = MutableStateFlow<List<VadEvent>>(emptyList())
    val events: StateFlow<List<VadEvent>> = _events

    private var speechStartMs = 0L
    private var silenceFrames = 0
    private val silenceThresholdFrames = 25  // 500ms at 20ms frames

    override fun onAudioFrame(frame: ShortArray) {
        val prob = engine.isSpeech(frame)
        val now = System.currentTimeMillis()

        when (_state.value) {
            SpeechState.SILENCE -> {
                if (prob > 0.5f) {
                    _state.value = SpeechState.SPEECH
                    speechStartMs = now
                    _events.value = _events.value + VadEvent.SpeechStart(now)
                    Logger.d("Speech started", "VAD")
                }
            }
            SpeechState.SPEECH -> {
                if (prob < 0.3f) {
                    silenceFrames++
                    if (silenceFrames >= silenceThresholdFrames) {
                        _state.value = SpeechState.SILENCE
                        val duration = now - speechStartMs
                        _events.value = _events.value + VadEvent.SpeechEnd(now, duration)
                        Logger.d("Speech ended (${duration}ms)", "VAD")
                        silenceFrames = 0
                    }
                } else {
                    silenceFrames = 0
                }
            }
        }
    }

    fun reset() {
        _state.value = SpeechState.SILENCE
        silenceFrames = 0
    }

    fun release() { engine.release() }
}
