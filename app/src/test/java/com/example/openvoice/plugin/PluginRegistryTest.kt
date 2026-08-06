package com.example.openvoice.plugin

import com.example.openvoice.plugin.example.TimePlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun lifecycleCallbacksAreSerializedAndBusyPluginCannotBeRemoved() = runTest {
        val registry = PluginRegistry()
        val plugin = BlockingPlugin(PluginManifest("blocking", "Blocking", "1", ""))
        registry.register(plugin)

        val enabling = launch { registry.enable(plugin.manifest.id) }
        plugin.enableStarted.await()
        assertTrue(registry.enable(plugin.manifest.id) is PluginLifecycleResult.Failed)
        assertFalse(registry.unregister(plugin.manifest.id))

        plugin.releaseEnable()
        enabling.join()
        assertEquals(PluginState.ENABLED, registry.state(plugin.manifest.id))
    }

    @Test
    fun cancellationPropagatesAndLifecycleStateCanBeRetried() = runTest {
        val registry = PluginRegistry()
        val plugin = BlockingPlugin(PluginManifest("cancel", "Cancel", "1", ""))
        registry.register(plugin)

        val enabling = launch { registry.enable(plugin.manifest.id) }
        plugin.enableStarted.await()
        enabling.cancel()
        enabling.join()

        assertEquals(PluginState.REGISTERED, registry.state(plugin.manifest.id))
        assertTrue(plugin.enableCancellationObserved)

        val retry = async { registry.enable(plugin.manifest.id) }
        plugin.releaseEnable()
        assertEquals(PluginLifecycleResult.Succeeded, retry.await())
    }

    @Test
    fun manifestSnapshotDoesNotChangeAfterRegistration() = runTest {
        val registry = PluginRegistry()
        val plugin = MutableManifestPlugin()
        registry.register(plugin)

        plugin.currentManifest = plugin.currentManifest.copy(name = "Changed")

        assertEquals("Original", registry.manifests().single().name)
        registry.enable("mutable")
        assertEquals("mutable", registry.dispatch(PluginRequest("ping")).single().pluginId)
    }

    @Test
    fun unregisterWaitsForDispatchToFinish() = runTest {
        val registry = PluginRegistry()
        val plugin = BlockingPlugin(PluginManifest("dispatch", "Dispatch", "1", ""))
        registry.register(plugin)
        registry.enable(plugin.manifest.id)

        val dispatching = launch { registry.dispatch(PluginRequest("ping")) }
        plugin.dispatchStarted.await()
        assertFalse(registry.unregister(plugin.manifest.id))

        plugin.releaseDispatch()
        dispatching.join()
        registry.disable(plugin.manifest.id)
        assertTrue(registry.unregister(plugin.manifest.id))
        assertNull(registry.state(plugin.manifest.id))
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

    private class MutableManifestPlugin : OpenVoicePlugin {
        var currentManifest = PluginManifest("mutable", "Original", "1", "")
        override val manifest: PluginManifest
            get() = currentManifest

        override suspend fun onEnable(context: PluginContext) = Unit
        override suspend fun onDisable() = Unit
        override suspend fun handle(request: PluginRequest): PluginResponse =
            PluginResponse.Handled("ok")
    }

    private class BlockingPlugin(
        override val manifest: PluginManifest
    ) : OpenVoicePlugin {
        var enableCancellationObserved = false
        val enableStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatchStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val enableRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val dispatchRelease = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun onEnable(context: PluginContext) {
            enableStarted.complete(Unit)
            try {
                enableRelease.await()
            } catch (error: CancellationException) {
                enableCancellationObserved = true
                throw error
            }
        }

        override suspend fun onDisable() = Unit

        override suspend fun handle(request: PluginRequest): PluginResponse {
            dispatchStarted.complete(Unit)
            dispatchRelease.await()
            return PluginResponse.Handled("ok")
        }

        fun releaseEnable() {
            enableRelease.complete(Unit)
        }

        fun releaseDispatch() {
            dispatchRelease.complete(Unit)
        }
    }
}
