package com.example.openvoice.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-safe subset of AudioCaptureManager: state, listener bookkeeping and the
 * stop() no-op path. The start() path needs a real Context + mic permission,
 * so it is covered by the instrumented suite on-device.
 */
class AudioCaptureManagerTest {

    private val manager = AudioCaptureManager(null)

    @Test
    fun startsIdleAndNotCapturing() {
        assertEquals(AudioState.IDLE, manager.state.value)
        assertFalse(manager.isCapturing())
    }

    @Test
    fun addAndRemoveListeners() {
        var calls = 0
        val listener = AudioFrameListener { calls++ }
        manager.addListener(listener)
        manager.addListener(listener)
        manager.removeListener(listener)
        manager.removeListener(listener)
        // No frames flow on the JVM; just verify bookkeeping doesn't throw.
        assertEquals(0, calls)
    }

    @Test
    fun startWithoutContextFailsGracefully() = runBlocking {
        // A null context means the RECORD_AUDIO permission cannot be verified,
        // so start() must fail gracefully into the ERROR state — never crash.
        val ok = manager.start()
        assertFalse(ok)
        assertEquals(AudioState.ERROR, manager.state.value)
        assertFalse(manager.isCapturing())
    }

    @Test
    fun stopOnIdleManagerIsSafeAndStaysIdle() {
        manager.stop()
        assertEquals(AudioState.IDLE, manager.state.value)
        assertFalse(manager.isCapturing())
    }

    @Test
    fun frameSizeConstantsAreConsistent() {
        // 20ms frames at 16kHz = 320 samples.
        assertEquals(16000, AudioCaptureManager.SAMPLE_RATE)
        assertEquals(20, AudioCaptureManager.FRAME_SIZE_MS)
        assertEquals(320, AudioCaptureManager.FRAME_SIZE)
    }
}
