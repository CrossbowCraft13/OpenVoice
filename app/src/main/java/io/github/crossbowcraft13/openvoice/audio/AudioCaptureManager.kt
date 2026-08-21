package io.github.crossbowcraft13.openvoice.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

fun interface AudioFrameListener {
    fun onAudioFrame(frame: ShortArray)
}

enum class AudioState { IDLE, LISTENING, ERROR }

@Singleton
class AudioCaptureManager @Inject constructor(private val context: Context?) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE_MS = 20
        const val FRAME_SIZE = SAMPLE_RATE * FRAME_SIZE_MS / 1000
    }

    private val _state = MutableStateFlow(AudioState.IDLE)
    val state: StateFlow<AudioState> = _state

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private var capturing = false
    private val listeners = mutableListOf<AudioFrameListener>()

    fun addListener(l: AudioFrameListener) { listeners.add(l) }
    fun removeListener(l: AudioFrameListener) { listeners.remove(l) }
    fun isCapturing() = capturing

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (capturing) return@withContext true
        if (context == null || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Logger.e("RECORD_AUDIO permission not granted", "Audio")
            _state.value = AudioState.ERROR
            return@withContext false
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Logger.e("Invalid buffer size", "Audio")
            _state.value = AudioState.ERROR
            return@withContext false
        }
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Logger.e("AudioRecord init failed", "Audio")
            _state.value = AudioState.ERROR
            return@withContext false
        }
        capturing = true
        _state.value = AudioState.LISTENING
        audioRecord?.startRecording()
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { loop() }
        Logger.i("Audio capture started", "Audio")
        true
    }

    fun stop() {
        capturing = false
        job?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _state.value = AudioState.IDLE
    }

    private suspend fun loop() {
        val buf = ShortArray(FRAME_SIZE)
        while (capturing) {
            val n = audioRecord?.read(buf, 0, FRAME_SIZE) ?: -1
            if (n > 0) {
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                _level.value = (Math.sqrt(sum / n).toFloat() / 32768f).coerceIn(0f, 1f)
                val frame = if (n < FRAME_SIZE) buf.copyOf(n) else buf.clone()
                for (l in listeners) try { l.onAudioFrame(frame) } catch (_: Exception) {}
            }
        }
    }
}
