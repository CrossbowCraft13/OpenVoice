package io.github.crossbowcraft13.openvoice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.crossbowcraft13.openvoice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PiperTtsEngine(private val context: Context?) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var sampleRate = 22050

    suspend fun loadModel(modelFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) { Logger.e("Piper model not found", "TTS"); return@withContext false }
        try {
            env = OrtEnvironment.getEnvironment("piper")
            session = env?.createSession(modelFile.absolutePath)
            // Sample rate metadata lookup is skipped; defaults to 22050 Hz.
            Logger.i("Piper loaded: ${modelFile.name} (${sampleRate}Hz)", "TTS")
            true
        } catch (e: Throwable) {
            // Missing model / no ONNX runtime: TTS must degrade gracefully,
            // never crash the pipeline.
            Logger.e("Piper load failed: ${e.message}", "TTS"); false
        }
    }

    suspend fun synthesize(text: String): ShortArray = withContext(Dispatchers.Default) {
        val sess = session ?: return@withContext ShortArray(0)
        if (text.isBlank()) return@withContext ShortArray(0)
        try {
            val inputIds = intArrayOf(0) + text.codePoints().toArray() + intArrayOf(1)
            val tensor = OnnxTensor.createTensor(env!!, java.nio.IntBuffer.wrap(inputIds), longArrayOf(1, inputIds.size.toLong()))
            // In production, use proper text encoder
            val output = sess.run(mapOf("input" to tensor))
            val audio = output[0].value as FloatArray
            ShortArray(audio.size) { (audio[it] * 32767f).toInt().toShort().coerceIn(Short.MIN_VALUE, Short.MAX_VALUE) }
        } catch (e: Throwable) { Logger.e("Piper synthesis error: ${e.message}", "TTS"); ShortArray(0) }
    }

    fun play(audio: ShortArray) {
        if (audio.isEmpty()) return
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(audio.size * 2).build()
            track.play(); track.write(audio, 0, audio.size); track.stop(); track.release()
        } catch (e: Exception) { Logger.e("Piper playback failed: ${e.message}", "TTS") }
    }

    fun release() { session?.close(); session = null }
}
