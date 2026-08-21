package io.github.crossbowcraft13.openvoice.vad

import io.github.crossbowcraft13.openvoice.util.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the VAD state machine (silence -> speech -> silence) on the JVM.
 *
 * A null context is safe: SileroVadEngine falls back to energy-based detection
 * when the ONNX model cannot load, so the state transitions are driven by the
 * same probability values the real engine would produce (0.0 for silence,
 * ~1.0 for a loud tone).
 */
class VadManagerTest {

    private lateinit var vad: VadManager

    @Before
    fun setup() {
        Logger.init(true)
        vad = VadManager(null)
    }

    private fun silenceFrame(n: Int = 320): ShortArray = ShortArray(n)

    private fun loudFrame(amplitude: Int = 8000, n: Int = 320): ShortArray =
        ShortArray(n) { i ->
            (Math.sin(2.0 * Math.PI * 440.0 * i / 16000.0) * amplitude).toInt().toShort()
        }

    @Test
    fun startsSilentWithNoEvents() {
        assertEquals(SpeechState.SILENCE, vad.state.value)
        assertTrue(vad.events.value.isEmpty())
    }

    @Test
    fun silenceNeverTriggersSpeech() {
        repeat(50) { vad.onAudioFrame(silenceFrame()) }
        assertEquals(SpeechState.SILENCE, vad.state.value)
        assertTrue(vad.events.value.isEmpty())
    }

    @Test
    fun loudAudioTriggersSpeechStart() {
        vad.onAudioFrame(loudFrame())
        assertEquals(SpeechState.SPEECH, vad.state.value)
        assertEquals(1, vad.events.value.size)
        assertTrue(vad.events.value.single() is VadEvent.SpeechStart)
    }

    @Test
    fun sustainedSilenceAfterSpeechEmitsSpeechEnd() {
        vad.onAudioFrame(loudFrame())
        assertEquals(SpeechState.SPEECH, vad.state.value)

        // 25 consecutive quiet frames (500ms at 20ms frames) are required.
        repeat(20) { vad.onAudioFrame(silenceFrame()) }
        assertEquals(SpeechState.SPEECH, vad.state.value)

        repeat(5) { vad.onAudioFrame(silenceFrame()) }
        assertEquals(SpeechState.SILENCE, vad.state.value)

        val end = vad.events.value.last()
        assertTrue("expected SpeechEnd, got $end", end is VadEvent.SpeechEnd)
        assertTrue("duration should be >= 0", (end as VadEvent.SpeechEnd).durationMs >= 0)
    }

    @Test
    fun interleavedLoudAudioKeepsSpeechAlive() {
        vad.onAudioFrame(loudFrame())
        vad.onAudioFrame(silenceFrame())
        vad.onAudioFrame(loudFrame())
        vad.onAudioFrame(silenceFrame())
        assertEquals(SpeechState.SPEECH, vad.state.value)
    }

    @Test
    fun resetReturnsToSilence() {
        vad.onAudioFrame(loudFrame())
        assertEquals(SpeechState.SPEECH, vad.state.value)

        vad.reset()
        assertEquals(SpeechState.SILENCE, vad.state.value)

        // After reset there is no active speech, so quiet frames emit nothing.
        vad.onAudioFrame(silenceFrame())
        assertTrue(vad.events.value.none { it is VadEvent.SpeechEnd })
    }

    @Test
    fun releaseIsSafeAndDoesNotCorruptState() {
        vad.release()
        vad.onAudioFrame(loudFrame())
        assertEquals(SpeechState.SPEECH, vad.state.value)
    }
}
