package com.example.openvoice.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * JVM-safe contract for the Piper TTS engine: with no ONNX model available,
 * every call degrades gracefully (false / empty audio) rather than crashing.
 */
class PiperTtsEngineTest {

    private val engine = PiperTtsEngine(null)

    @Test
    fun loadModelRejectsMissingFile() = runBlocking {
        assertFalse(engine.loadModel(File("no-such-model.onnx")))
    }

    @Test
    fun synthesizeWithoutModelReturnsEmptyAudio() = runBlocking {
        assertEquals(0, engine.synthesize("hello").size)
    }

    @Test
    fun blankTextSynthesizesToEmptyWhenNoModel() = runBlocking {
        assertEquals(0, engine.synthesize("   ").size)
    }

    @Test
    fun playIgnoresEmptyAudioAndToleratesNoDevice() {
        engine.play(ShortArray(0))
        // Non-empty audio with no session: AudioTrack construction fails on the
        // JVM and is swallowed by the engine's error handling.
        engine.play(ShortArray(16000))
    }

    @Test
    fun releaseIsSafe() {
        engine.release()
        engine.release()
    }
}
