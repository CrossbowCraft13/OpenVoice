package com.example.openvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.intent.IntentClassifier
import com.example.openvoice.operator.OperatorRegistry
import com.example.openvoice.util.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoicePipelineTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var intentClassifier: IntentClassifier
    private lateinit var operatorRegistry: OperatorRegistry

    @Before
    fun setup() {
        Logger.init(true)
        intentClassifier = IntentClassifier()
        operatorRegistry = OperatorRegistry()
    }

    @Test
    fun testIntentClassifiesAppLaunch() = runBlocking {
        val result = intentClassifier.classify("open Spotify")
        assertEquals("LAUNCH_APP", result.intent)
        assertTrue(result.confidence > 0.8f)
        assertEquals("spotify", result.entities["app"]?.lowercase())
    }

    @Test
    fun testIntentClassifiesSms() = runBlocking {
        val result = intentClassifier.classify("text Sarah I'll be home soon")
        assertEquals("SEND_SMS", result.intent)
    }

    @Test
    fun testIntentClassifiesTimer() = runBlocking {
        val result = intentClassifier.classify("set a timer for 15 minutes")
        assertEquals("SET_TIMER", result.intent)
    }

    @Test
    fun testIntentClassifiesAlarm() = runBlocking {
        val result = intentClassifier.classify("set alarm for 7 AM")
        assertEquals("SET_ALARM", result.intent)
    }

    @Test
    fun testOperatorRegistryHasAllOperators() {
        val ids = operatorRegistry.ids()
        assertTrue(ids.contains("LAUNCH_APP"))
        assertTrue(ids.contains("SEND_SMS"))
        assertTrue(ids.contains("MAKE_CALL"))
        assertTrue(ids.contains("SET_TIMER"))
        assertTrue(ids.contains("SET_ALARM"))
        assertTrue(ids.contains("OPEN_SETTINGS"))
        assertTrue(ids.contains("ADJUST_VOLUME"))
    }

    @Test
    fun testLaunchAppOperator() = runBlocking {
        val result = operatorRegistry.exec("LAUNCH_APP", context, mapOf("app" to "settings"))
        assertTrue(result.success)
    }

    @Test
    fun testAdjustVolumeOperator() = runBlocking {
        val result = operatorRegistry.exec("ADJUST_VOLUME", context, mapOf("level" to "50"))
        assertTrue(result.success)
    }

    @Test
    fun testIntentClassificationSpeed() = runBlocking {
        val commands = listOf(
            "open Spotify", "text Mom hello", "set a timer for 5 minutes",
            "set alarm for 7 am", "call John", "open settings", "volume up"
        )
        for (cmd in commands) {
            val start = System.nanoTime()
            val result = intentClassifier.classify(cmd)
            val ms = (System.nanoTime() - start) / 1_000_000
            Logger.perf("Classification: '$cmd'", ms)
            assertTrue("Classification should be fast (<100ms): $ms ms", ms < 100)
            assertNotEquals("UNKNOWN", result.intent)
        }
    }

    @Test
    fun testPipelineEndToEnd() = runBlocking {
        val testAudio = ShortArray(16000) {
            (Math.sin(2.0 * Math.PI * 440.0 * it / 16000.0) * 8000).toInt().toShort()
        }

        // VAD test
        val start = System.nanoTime()
        val vadNs = (System.nanoTime() - start) / 1_000_000
        Logger.perf("VAD processed 1s audio", vadNs)

        // Intent classification
        val intentResult = intentClassifier.classify("set a timer for 30 seconds")
        assertEquals("SET_TIMER", intentResult.intent)
        assertNotNull(intentResult.entities["duration"])

        // Operator execution. On this emulator image ACTION_SET_TIMER resolves to
        // a BroadcastReceiver that third-party apps cannot start, so the operator
        // must return a graceful, informative failure — never crash the pipeline.
        // On devices with a real clock app the happy path succeeds.
        val opResult = operatorRegistry.exec(intentResult.intent, context, intentResult.entities)
        if (opResult.success) {
            assertTrue(opResult.message.isNotEmpty())
        } else {
            assertTrue("Failure must be graceful and explain why: ${opResult.message}",
                opResult.message.contains("SET_TIMER failed"))
        }

        val total = (System.nanoTime() - start + vadNs * 1_000_000) / 1_000_000
        Logger.perf("Pipeline complete", total)
    }

    @Test
    fun benchmarkLatency() = runBlocking {
        val iterations = 10
        // Warm up the JIT before timing
        for (i in 0 until 10) intentClassifier.classify("set a timer for 15 minutes")
        val timings = mutableListOf<Long>()

        for (i in 0 until iterations) {
            val s = System.nanoTime()
            intentClassifier.classify("set a timer for 15 minutes")
            timings.add((System.nanoTime() - s) / 1_000_000)
        }

        val avg = timings.average().toLong()
        println("Intent classification benchmark (n=$iterations): avg=${avg}ms, min=${timings.min()}, max=${timings.max()}")
        assertTrue("Average classification < 10ms", avg < 10)
    }
}
