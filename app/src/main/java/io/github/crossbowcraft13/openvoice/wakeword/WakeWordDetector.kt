package io.github.crossbowcraft13.openvoice.wakeword

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.crossbowcraft13.openvoice.util.Logger

sealed class WakeWordEvent {
    data class Detected(val word: String, val confidence: Float, val ts: Long) : WakeWordEvent()
    object Idle : WakeWordEvent()
}

class WakeWordDetector(context: Context?) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val threshold = 0.5f
    private var debounceMs = 0L

    private val melBins = 40
    private val frameSize = 80   // 5ms at 16kHz
    private val numFrames = 16
    private val audioBuffer = FloatArray(frameSize * numFrames)
    private var bufPos = 0

    init {
        try {
            env = OrtEnvironment.getEnvironment("wakeword")
            val bytes = context!!.assets.open("openwakeword.onnx").use { it.readBytes() }
            session = env?.createSession(bytes)
            Logger.i("OpenWakeWord loaded", "WakeWord")
        } catch (e: Throwable) {
            // Missing model asset / no ONNX runtime: the detector must degrade
            // gracefully (returns null from processAudio) rather than crash.
            Logger.e("OpenWakeWord load failed: ${e.message}", "WakeWord")
        }
    }

    fun processAudio(frame: ShortArray): WakeWordEvent? {
        val sess = session ?: return null
        val now = System.currentTimeMillis()
        if (now - debounceMs < 2000) return null

        feedAudio(frame)
        if (bufPos < frameSize * numFrames) return null

        val features = computeFeatures() ?: return null
        return try {
            val shape = longArrayOf(1, numFrames.toLong(), melBins.toLong(), 1)
            val tensor = OnnxTensor.createTensor(env!!, java.nio.FloatBuffer.wrap(features), shape)
            val output = sess.run(mapOf("input" to tensor))
            val scores = (output[0].value as Array<FloatArray>)[0]
            val wakeScore = scores.getOrElse(1) { 0f }
            if (wakeScore > threshold) {
                debounceMs = now
                WakeWordEvent.Detected("openvoice", wakeScore, now)
            } else WakeWordEvent.Idle
        } catch (e: Exception) {
            null
        }
    }

    // Internal so the DSP (windowing + FFT + mel) can be unit tested directly
    // on the JVM, independent of the ONNX session.
    internal fun feedAudio(frame: ShortArray) {
        for (s in frame) {
            audioBuffer[bufPos % audioBuffer.size] = s.toFloat() / 32768f
            bufPos++
        }
    }

    internal fun computeFeatures(): FloatArray? {
        val features = FloatArray(numFrames * melBins)
        for (f in 0 until numFrames) {
            val offset = (bufPos - numFrames * frameSize + f * frameSize).mod(audioBuffer.size)
            val frame = FloatArray(frameSize)
            for (i in 0 until frameSize) {
                val idx = (offset + i).mod(audioBuffer.size)
                frame[i] = audioBuffer[idx] * (0.5f - (0.5f * Math.cos(2.0 * Math.PI * i / (frameSize - 1))).toFloat())
            }
            val mag = fftMagnitude(frame)
            for (m in 0 until melBins) {
                val bin = ((m.toFloat() / melBins) * mag.size).toInt().coerceIn(0, mag.size - 1)
                features[f * melBins + m] = Math.log((mag[bin] + 1e-10f).toDouble()).toFloat()
            }
        }
        return features
    }

    internal fun fftMagnitude(frame: FloatArray): FloatArray {
        // The radix-2 FFT requires a power-of-two length. The frame size (80
        // samples, 5ms @16kHz) is not a power of two, so pad with zeros to the
        // next power of two — zero-padding is the correct DFT for this case.
        // (Without padding this loop read past the array end and crashed once
        // the buffer filled with the ONNX model loaded.)
        var n = 1
        while (n < frame.size) n = n shl 1
        val re = FloatArray(n) { if (it < frame.size) frame[it] else 0f }
        val im = FloatArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            var wR = Math.cos(angle).toFloat()
            var wI = Math.sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curR = 1f; var curI = 0f
                for (k in 0 until len / 2) {
                    val uR = re[i + k]; val uI = im[i + k]
                    val vR = re[i + k + len / 2] * curR - im[i + k + len / 2] * curI
                    val vI = re[i + k + len / 2] * curI + im[i + k + len / 2] * curR
                    re[i + k] = uR + vR; im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR; im[i + k + len / 2] = uI - vI
                    val tmp = curR * wR - curI * wI; curI = curR * wI + curI * wR; curR = tmp
                }
                i += len
            }
            len = len shl 1
        }
        return FloatArray(n / 2 + 1) { Math.sqrt((re[it] * re[it] + im[it] * im[it]).toDouble()).toFloat() }
    }

    fun release() { session?.close(); session = null }
}
