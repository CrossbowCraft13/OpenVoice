package com.example.openvoice.plugin

import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns plugin registration, lifecycle, and dispatch for the in-process SDK.
 *
 * The registry deliberately does not discover arbitrary classes or load remote code. A future
 * marketplace can build on this contract without weakening the host's local-first boundary.
 */
@Singleton
class PluginRegistry @Inject constructor() {

    private data class Entry(
        val plugin: OpenVoicePlugin,
        val manifest: PluginManifest,
        var state: PluginState = PluginState.REGISTERED,
        var lifecycleInProgress: Boolean = false,
        var dispatchInProgress: Int = 0
    )

    private sealed interface LifecycleStart {
        data class Run(val entry: Entry, val previousState: PluginState) : LifecycleStart
        data class Complete(val result: PluginLifecycleResult) : LifecycleStart
    }

    private val entries = linkedMapOf<String, Entry>()

    @Synchronized
    fun register(plugin: OpenVoicePlugin): PluginRegistrationResult {
        val manifest = plugin.manifest
        if (!manifest.id.matches(PLUGIN_ID)) {
            return PluginRegistrationResult.Rejected("Invalid plugin id: ${manifest.id}")
        }
        if (manifest.name.isBlank() || manifest.version.isBlank()) {
            return PluginRegistrationResult.Rejected("Plugin name and version are required")
        }
        if (entries.containsKey(manifest.id)) {
            return PluginRegistrationResult.Rejected("Plugin already registered: ${manifest.id}")
        }
        // Keep the validated manifest stable even if a plugin exposes mutable metadata.
        entries[manifest.id] = Entry(
            plugin = plugin,
            manifest = manifest.copy(permissions = manifest.permissions.toSet())
        )
        return PluginRegistrationResult.Registered
    }

    @Synchronized
    fun unregister(pluginId: String): Boolean {
        val entry = entries[pluginId] ?: return false
        // Only known-inactive states are removable. A failed callback may have partially
        // initialized resources, so require a successful disable before removal.
        if ((entry.state != PluginState.REGISTERED && entry.state != PluginState.DISABLED) ||
            entry.lifecycleInProgress ||
            entry.dispatchInProgress > 0
        ) {
            return false
        }
        entries.remove(pluginId)
        return true
    }

    @Synchronized
    fun manifests(): List<PluginManifest> = entries.values.map { it.manifest }

    @Synchronized
    fun state(pluginId: String): PluginState? = entries[pluginId]?.state

    suspend fun enable(
        pluginId: String,
        context: PluginContext = PluginContext()
    ): PluginLifecycleResult {
        return when (val start = beginLifecycle(pluginId, enabling = true)) {
            is LifecycleStart.Complete -> start.result
            is LifecycleStart.Run -> completeLifecycle(start, PluginState.ENABLED) {
                start.entry.plugin.onEnable(context)
            }
        }
    }

    suspend fun disable(pluginId: String): PluginLifecycleResult {
        return when (val start = beginLifecycle(pluginId, enabling = false)) {
            is LifecycleStart.Complete -> start.result
            is LifecycleStart.Run -> completeLifecycle(start, PluginState.DISABLED) {
                start.entry.plugin.onDisable()
            }
        }
    }

    suspend fun dispatch(request: PluginRequest): List<PluginInvocation> {
        val activeEntries = synchronized(this) {
            entries.values
                .filter { it.state == PluginState.ENABLED && !it.lifecycleInProgress }
                .onEach { it.dispatchInProgress++ }
                .toList()
        }
        return try {
            activeEntries.mapNotNull { entry ->
                try {
                    when (val response = entry.plugin.handle(request)) {
                        PluginResponse.NotHandled -> null
                        else -> PluginInvocation(entry.manifest.id, response)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    PluginInvocation(
                        entry.manifest.id,
                        PluginResponse.Failed(error.message ?: "Plugin request failed")
                    )
                }
            }
        } finally {
            synchronized(this) {
                activeEntries.forEach { entry -> entry.dispatchInProgress-- }
            }
        }
    }

    private fun beginLifecycle(pluginId: String, enabling: Boolean): LifecycleStart = synchronized(this) {
        val entry = entries[pluginId]
            ?: return@synchronized LifecycleStart.Complete(
                PluginLifecycleResult.Failed("Unknown plugin: $pluginId")
            )
        if (entry.lifecycleInProgress || entry.dispatchInProgress > 0) {
            return@synchronized LifecycleStart.Complete(
                PluginLifecycleResult.Failed("Plugin is busy: $pluginId")
            )
        }
        val alreadyInTargetState = if (enabling) {
            entry.state == PluginState.ENABLED
        } else {
            entry.state == PluginState.DISABLED
        }
        if (alreadyInTargetState) {
            return@synchronized LifecycleStart.Complete(PluginLifecycleResult.Succeeded)
        }
        entry.lifecycleInProgress = true
        LifecycleStart.Run(entry, entry.state)
    }

    private suspend fun completeLifecycle(
        start: LifecycleStart.Run,
        successState: PluginState,
        callback: suspend () -> Unit
    ): PluginLifecycleResult {
        return try {
            callback()
            synchronized(this) {
                start.entry.state = successState
                start.entry.lifecycleInProgress = false
            }
            PluginLifecycleResult.Succeeded
        } catch (error: CancellationException) {
            synchronized(this) {
                start.entry.state = start.previousState
                start.entry.lifecycleInProgress = false
            }
            throw error
        } catch (error: Exception) {
            synchronized(this) {
                start.entry.state = PluginState.FAILED
                start.entry.lifecycleInProgress = false
            }
            PluginLifecycleResult.Failed(error.message ?: "Plugin lifecycle operation failed")
        }
    }

    companion object {
        private val PLUGIN_ID = Regex("[a-z0-9]+([._-][a-z0-9]+)*")
    }
}
