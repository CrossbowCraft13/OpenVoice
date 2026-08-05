package com.example.openvoice.plugin

import com.example.openvoice.plugin.example.TimePlugin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryTest {

    @Test
    fun registerRejectsDuplicateAndInvalidIds() {
        val registry = PluginRegistry()
        val plugin = TimePlugin()

        assertEquals(PluginRegistrationResult.Registered, registry.register(plugin))
        assertTrue(registry.register(plugin) is PluginRegistrationResult.Rejected)
        assertTrue(
            registry.register(StubPlugin(PluginManifest("Bad Id", "Bad", "1", "bad")))
                is PluginRegistrationResult.Rejected
        )
    }

    @Test
    fun lifecycleEnablesDispatchAndDisablesPlugin() = runTest {
        val registry = PluginRegistry()
        val plugin = TimePlugin()
        registry.register(plugin)

        assertEquals(PluginState.REGISTERED, registry.state(plugin.manifest.id))
        assertEquals(PluginLifecycleResult.Succeeded, registry.enable(plugin.manifest.id))
        assertEquals(PluginState.ENABLED, registry.state(plugin.manifest.id))
        assertEquals(
            listOf(PluginResponse.Handled("pong", mapOf("plugin" to plugin.manifest.id))),
            registry.dispatch(PluginRequest("ping")).map { it.response }
        )

        assertEquals(PluginLifecycleResult.Succeeded, registry.disable(plugin.manifest.id))
        assertTrue(registry.dispatch(PluginRequest("ping")).isEmpty())
        assertEquals(PluginState.DISABLED, registry.state(plugin.manifest.id))
    }

    @Test
    fun dispatchIgnoresNotHandledAndContainsPluginFailures() = runTest {
        val registry = PluginRegistry()
        registry.register(StubPlugin(PluginManifest("first", "First", "1", "")))
        registry.register(StubPlugin(PluginManifest("second", "Second", "1", ""), failure = true))
        registry.enable("first")
        registry.enable("second")

        val results = registry.dispatch(PluginRequest("unknown"))
        assertEquals(1, results.size)
        assertEquals("second", results.single().pluginId)
        assertTrue(results.single().response is PluginResponse.Failed)
    }

    @Test
    fun unknownLifecycleAndUnregisterAreSafe() = runTest {
        val registry = PluginRegistry()
        assertFalse(registry.unregister("missing"))
        assertTrue(registry.enable("missing") is PluginLifecycleResult.Failed)
        assertTrue(registry.disable("missing") is PluginLifecycleResult.Failed)
    }

    private class StubPlugin(
        override val manifest: PluginManifest,
        private val failure: Boolean = false
    ) : OpenVoicePlugin {
        private var enabled = false

        override suspend fun onEnable(context: PluginContext) {
            enabled = true
        }

        override suspend fun onDisable() {
            enabled = false
        }

        override suspend fun handle(request: PluginRequest): PluginResponse {
            if (failure) error("boom")
            if (!enabled || request.command != "handled") return PluginResponse.NotHandled
            return PluginResponse.Handled("handled")
        }
    }
}
