package io.github.crossbowcraft13.openvoice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the wake-word detector's graceful no-model path plus the DSP math
 * (windowing + FFT + mel features) that runs before the ONNX session.
 */
class WakeWordDetectorTest {

    @Test
    fun constructorSurvivesMissingModel() {
        // On the JVM the ONNX model cannot load; construction must not crash
        // and audio processing must degrade to null.
        val detector = WakeWordDetector(null)
        assertNull(detector.processAudio(ShortArray(320)))
        assertNull(detector.processAudio(ShortArray(16000)))
        detector.release()
    }

    @Test
    fun fftMagnitudeFindsSinePeak() {
        val detector = WakeWordDetector(null)
        val n = 80
        // 1 kHz tone at 16 kHz sample rate, zero-padded to n=128
        // -> bin = 1000/16000 * 128 = 8.
        val frame = FloatArray(n) { i ->
            Math.sin(2.0 * Math.PI * 1000.0 * i / 16000.0).toFloat()
        }
        val mag = detector.fftMagnitude(frame)
        assertEquals(128 / 2 + 1, mag.size)

        var peakBin = 0
        for (i in 1 until mag.size) if (mag[i] > mag[peakBin]) peakBin = i
        assertEquals("peak should sit at bin 8", 8, peakBin)
    }

    @Test
    fun fftMagnitudeOfSilenceIsZero() {
        val detector = WakeWordDetector(null)
        val mag = detector.fftMagnitude(FloatArray(80))
        assertTrue(mag.all { it == 0f })
    }

    @Test
    fun computeFeaturesReturnsFiniteMelValues() {
        val detector = WakeWordDetector(null)
        val features = detector.computeFeatures()!!
        assertEquals(16 * 40, features.size)
        assertTrue(features.all { it.isFinite() })
    }

    @Test
    fun computeFeaturesOfLoudToneHasLargerMagnitudeThanSilence() {
        val detector = WakeWordDetector(null)
        val silence = detector.computeFeatures()!!
        // Fill the internal ring buffer with a loud tone via the DSP feed path.
        val tone = ShortArray(320) { i ->
            (Math.sin(2.0 * Math.PI * 1000.0 * i / 16000.0) * 12000).toInt().toShort()
        }
        repeat(4) { detector.feedAudio(tone) } // 4 * 320 = 1280 samples = full buffer
        val toneFeatures = detector.computeFeatures()!!
        val silSum = silence.sum()
        val toneSum = toneFeatures.sum()
        assertTrue(
            "tone features ($toneSum) should be louder than silence ($silSum)",
            toneSum > silSum
        )
    }
}
