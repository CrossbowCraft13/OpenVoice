package com.example.openvoice.vad

import android.content.Context
import com.example.openvoice.util.Logger
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

class SileroVadEngine(context: Context?) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val threshold: Float = 0.5f

    init {
        try {
            env = OrtEnvironment.getEnvironment("silero")
            val modelBytes = context!!.assets.open("silero_vad.onnx").use { it.readBytes() }
            session = env?.createSession(modelBytes)
            Logger.i("Silero VAD loaded", "VAD")
        } catch (e: Throwable) {
            // Model/assets unavailable (missing asset, no ONNX runtime on this
            // platform, ...). A VAD load failure must never crash the app — the
            // energy-based fallback keeps the pipeline functional.
            Logger.e("Silero VAD load failed: ${e.message}", "VAD")
        }
    }

    fun isSpeech(audio: ShortArray): Float {
        val sess = session ?: return fallbackEnergy(audio)
        return try {
            val floatData = FloatArray(audio.size) { audio[it].toFloat() / 32768f }
            val shape = longArrayOf(1, audio.size.toLong())
            val tensor = OnnxTensor.createTensor(env!!, java.nio.FloatBuffer.wrap(floatData), shape)
            val inputs: Map<String, OnnxTensor> = mapOf("input" to tensor)
            val output = sess.run(inputs)
            val result = output[0].value as FloatArray
            result[0].coerceIn(0f, 1f)
        } catch (e: Exception) {
            fallbackEnergy(audio)
        }
    }

    private fun fallbackEnergy(audio: ShortArray): Float {
        var sum = 0.0
        for (s in audio) sum += s.toDouble() * s
        val rms = Math.sqrt(sum / audio.size).toFloat() / 32768f
        return (rms * 10f).coerceIn(0f, 1f)
    }

    fun release() {
        session?.close()
        session = null
    }
}
