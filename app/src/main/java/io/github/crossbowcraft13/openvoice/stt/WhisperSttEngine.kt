package io.github.crossbowcraft13.openvoice.stt

import android.content.Context
import io.github.crossbowcraft13.openvoice.util.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperSttEngine(private val context: Context?) {

    companion object {
        private var nativeLoaded = false

        init {
            try {
                // The native library is built as "whisper_bridge" by CMakeLists.txt.
                System.loadLibrary("whisper_bridge")
                nativeLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Logger.w("whisper.cpp native library not available: ${e.message}", "STT")
            }
        }
    }

    private external fun nativeInit(path: String): Long
    private external fun nativeTranscribe(ctx: Long, audio: ShortArray, len: Int): String
    private external fun nativeRelease(ctx: Long)
    private external fun nativeResetState(ctx: Long)

    private var ctxPtr = 0L

    fun initialize(modelFile: File): Boolean {
        if (!nativeLoaded) {
            Logger.e("Whisper native library not loaded", "STT")
            return false
        }
        if (!modelFile.exists()) { Logger.e("Whisper model not found: ${modelFile.absolutePath}", "STT"); return false }
        return try {
            ctxPtr = nativeInit(modelFile.absolutePath)
            if (ctxPtr != 0L) { Logger.i("Whisper initialized: ${modelFile.name}", "STT"); true }
            else { Logger.e("Whisper init returned null", "STT"); false }
        } catch (e: UnsatisfiedLinkError) {
            Logger.e("Native lib not loaded: ${e.message}", "STT"); false
        }
    }

    fun transcribe(audio: ShortArray): String {
        if (ctxPtr == 0L || audio.isEmpty()) return ""
        return try { nativeTranscribe(ctxPtr, audio, audio.size) } catch (e: Exception) { Logger.e("Transcribe error: ${e.message}", "STT"); "" }
    }

    fun resetState() { if (ctxPtr != 0L) nativeResetState(ctxPtr) }
    fun isInitialized() = ctxPtr != 0L
    fun release() { if (ctxPtr != 0L) { nativeRelease(ctxPtr); ctxPtr = 0L } }
}
