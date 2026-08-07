package com.example.openvoice.operator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for OperatorRegistry. A null context is intentional: operators
 * that need Android system access must fail gracefully (never throw through
 * exec), while context-free operators (HELP/STOP/QUERY) must succeed.
 */
class OperatorRegistryJvmTest {

    private val registry = OperatorRegistry()

    @Test
    fun helpStopAndQuerySucceedWithoutContext() = runBlocking {
        for (id in listOf("HELP", "STOP", "QUERY")) {
            val result = registry.exec(id, null, emptyMap())
            assertTrue("$id should succeed without context: ${result.message}", result.success)
            assertTrue(result.message.isNotEmpty())
        }
    }

    @Test
    fun unknownOperatorFailsGracefully() = runBlocking {
        val result = registry.exec("NOT_A_REAL_OP", null, emptyMap())
        assertFalse(result.success)
        assertTrue(result.message.contains("Unknown operator"))
    }

    @Test
    fun launchAppWithoutNameFailsBeforeContextUse() = runBlocking {
        val result = registry.exec("LAUNCH_APP", null, emptyMap())
        assertFalse(result.success)
        assertEquals("No app specified", result.message)
    }

    @Test
    fun smsWithoutRecipientFailsGracefully() = runBlocking {
        val result = registry.exec("SEND_SMS", null, mapOf("message" to "hi"))
        assertFalse(result.success)
        assertEquals("No recipient", result.message)
    }

    @Test
    fun timerWithoutDurationFailsGracefully() = runBlocking {
        val result = registry.exec("SET_TIMER", null, emptyMap())
        assertFalse(result.success)
        assertEquals("No duration", result.message)
    }

    @Test
    fun timerWithDurationFailsGracefullyOnSystemDenial() = runBlocking {
        // parseDuration runs before any Android call; the null-context failure
        // must be wrapped in a graceful OperatorResult, never an exception.
        val result = registry.exec("SET_TIMER", null, mapOf("duration" to "5 minutes"))
        assertFalse(result.success)
        assertTrue(result.message.startsWith("SET_TIMER failed"))
    }

    @Test
    fun knownAppsMapContainsCoreEntries() {
        val known = OperatorRegistry.KNOWN_APPS
        assertTrue(known.containsKey("spotify"))
        assertTrue(known.containsKey("youtube"))
        assertTrue(known.containsKey("clock"))
        assertEquals("com.android.settings", known["settings"])
    }
}
