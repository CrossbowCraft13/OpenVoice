package com.example.openvoice.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure computation in DeviceProfiler (model requirement estimates).
 * The context-dependent capability probes (ActivityManager, storage, thermal)
 * are exercised by the instrumented suite on-device.
 */
class DeviceProfilerTest {

    private val profiler = DeviceProfiler(null)

    @Test
    fun estimateModelRequirementsScalesWithParams() {
        val tiny = profiler.estimateModelRequirements(0.5f)
        val medium = profiler.estimateModelRequirements(3.8f)
        val big = profiler.estimateModelRequirements(7f)

        assertTrue("tiny model must need some RAM", tiny.ramRequiredMb > 0)
        assertTrue("bigger models need more RAM", big.ramRequiredMb > tiny.ramRequiredMb)
        assertTrue(medium.ramRequiredMb in tiny.ramRequiredMb..big.ramRequiredMb)
        assertTrue(tiny.storageRequiredMb > 0)
        assertTrue(tiny.initialContextTokens > 0)
    }

    @Test
    fun modelTiersHaveAscendingParamLimits() {
        val tiers = DeviceProfiler.ModelTier.values()
        for (i in 1 until tiers.size) {
            assertTrue(
                "tier ${tiers[i].name} must allow more params than ${tiers[i - 1].name}",
                tiers[i].maxParams > tiers[i - 1].maxParams
            )
        }
    }

    @Test
    fun recommendModelWithoutDeviceInfoFallsBackToNone() {
        // Null context -> default capabilities (0 RAM) -> no model can run.
        assertEquals(DeviceProfiler.ModelTier.NONE, profiler.recommendModel().tier)
        assertEquals("none", profiler.recommendModel().suggestedModel)
    }

    @Test
    fun recommendedThreadsFitsCpuBounds() {
        val req = profiler.estimateModelRequirements(1f)
        assertTrue("threads should be 2..6, was ${req.recommendedThreads}", req.recommendedThreads in 2..6)
    }
}
