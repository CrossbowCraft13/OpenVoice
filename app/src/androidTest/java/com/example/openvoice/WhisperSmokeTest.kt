package com.example.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.openvoice.stt.WhisperSttEngine
import com.example.openvoice.util.Logger
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-inference smoke test for the whisper.cpp native integration.
 *
 * Debug builds also target x86_64 (see abiFilters in app/build.gradle.kts), so
 * the emulator loads the REAL libwhisper_bridge.so and this test drives actual
 * whisper.cpp transcription end to end. The tiny model + a known speech WAV are
 * NOT checked in; provide them via instrumentation arguments or stage them into
 * the app's internal files/smoke dir (internal storage avoids the Android 11+
 * FUSE layer that hides shell-pushed files in external Android/data):
 *
 *   adb push ggml-tiny.en.bin /data/local/tmp/
 *   adb push vendor/whisper.cpp/samples/jfk.wav /data/local/tmp/
 *   adb shell "run-as com.example.openvoice.debug sh -c \
 *     'mkdir -p files/smoke && cat /data/local/tmp/ggml-tiny.en.bin > files/smoke/ggml-tiny.en.bin && \
 *      cat /data/local/tmp/jfk.wav > files/smoke/jfk.wav'"
 *   adb shell am instrument -w -e class com.example.openvoice.WhisperSmokeTest \
 *       com.example.openvoice.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Like NativeSmokeTest, files/smoke deliberately avoids files/models so this
 * stays hermetic against ModelManagerCoverageTest's exact-count assertions.
 * Without a staged model the test is skipped (CI stays green); with one it
 * proves loadModel → real transcription → release all execute in native code.
 */
@RunWith(AndroidJUnit4::class)
class WhisperSmokeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        Logger.init(true)
    }

    private fun stagedFile(name: String): File? {
        val smokeDir = File(context.filesDir, "smoke")
        return File(smokeDir, name).takeIf { it.isFile && it.length() > 0 }
    }

    /** Minimal WAV reader: 16-bit PCM, any channel count (mixed to mono), 8-48kHz. */
    private fun readWavPcm16(wav: File): ShortArray {
        val bytes = wav.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.get(0) == 'R'.code.toByte() && bb.get(1) == 'I'.code.toByte() &&
            bb.get(2) == 'F'.code.toByte() && bb.get(3) == 'F'.code.toByte()) { "not a RIFF file" }
        var pos = 12
        var format = 0
        var channels = 1
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = bb.getInt(pos + 4)
            val dataStart = pos + 8
            if (chunkId == "fmt ") {
                format = bb.getShort(dataStart).toInt()
                channels = bb.getShort(dataStart + 2).toInt()
                bits = bb.getShort(dataStart + 14).toInt()
            } else if (chunkId == "data") {
                require(format == 1) { "only PCM (format 1) supported, got $format" }
                require(bits == 16) { "only 16-bit supported, got $bits" }
                val sampleCount = chunkSize / 2
                val out = ShortArray(sampleCount / channels)
                var o = 0
                for (i in 0 until sampleCount step channels) {
                    out[o++] = bb.getShort(dataStart + i * 2)
                }
                return out
            }
            pos = dataStart + chunkSize + (chunkSize % 2)
        }
        error("no data chunk found")
    }

    @Test
    fun realWhisperTranscription_roundTrip() {
        val args = InstrumentationRegistry.getArguments()
        val model = args.getString("whisperModel")?.let { File(it) } ?: stagedFile("ggml-tiny.en.bin")
        val wav = args.getString("whisperWav")?.let { File(it) } ?: stagedFile("jfk.wav")
        assumeTrue(
            "no whisper model/wav staged; skipping real-inference smoke " +
                "(pass -e whisperModel/-e whisperWav or stage into files/smoke)",
            model != null && model.isFile && model.length() > 0 &&
                wav != null && wav.isFile && wav.length() > 0
        )

        val engine = WhisperSttEngine(context)
        try {
            Logger.i("WhisperSmokeTest: loading ${model!!.absolutePath}", "STT")
            assertTrue("initialize must succeed on a real whisper model", engine.initialize(model))

            val audio = readWavPcm16(wav!!)
            Logger.i("WhisperSmokeTest: transcribing ${audio.size} samples (${audio.size / 16000}s)", "STT")

            val text = engine.transcribe(audio)
            Logger.i("WhisperSmokeTest: transcribed '${text.take(160)}'", "STT")
            assertTrue("transcription must produce text, got: '${text.take(80)}'", text.isNotBlank())
            // jfk.wav is "and so my fellow Americans, ..." — tiny.en transcribes
            // it reliably; the word "fellow" is the canonical check.
            assertTrue(
                "expected 'fellow' in transcription, got: '${text.take(200)}'",
                text.contains("fellow", ignoreCase = true)
            )

            engine.resetState() // must not crash
        } finally {
            engine.release()
        }
        assertTrue("release must clear the native context", !engine.isInitialized())
    }
}
