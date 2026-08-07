package com.example.openvoice.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * On the JVM the whisper_bridge native library is never present, so these
 * tests lock in the graceful-degradation contract: every call must return a
 * benign value instead of crashing the pipeline.
 */
class WhisperSttEngineTest {

    private val engine = WhisperSttEngine(null)

    @Test
    fun initializeFailsGracefullyWithoutNativeLibrary() {
        assertFalse(engine.initialize(File("nonexistent-model.bin")))
    }

    @Test
    fun neverInitializedMeansNoContext() {
        assertFalse(engine.isInitialized())
    }

    @Test
    fun transcribeWithoutContextReturnsEmpty() {
        assertEquals("", engine.transcribe(ShortArray(0)))
        assertEquals("", engine.transcribe(ShortArray(160)))
    }

    @Test
    fun lifecycleCallsAreSafeWithoutNative() {
        engine.resetState()
        engine.release()
        assertFalse(engine.isInitialized())
        // A second release must be a no-op.
        engine.release()
    }
}
