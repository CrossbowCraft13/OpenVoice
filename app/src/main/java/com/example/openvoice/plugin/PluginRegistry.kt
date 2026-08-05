package com.example.openvoice.plugin

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
        var state: PluginState = PluginState.REGISTERED
    )

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
        entries[manifest.id] = Entry(plugin)
        return PluginRegistrationResult.Registered
    }

    @Synchronized
    fun unregister(pluginId: String): Boolean {
        val entry = entries[pluginId] ?: return false
        // An enabled plugin must be disabled first so its cleanup callback always runs.
        if (entry.state == PluginState.ENABLED) return false
        entries.remove(pluginId)
        return true
    }

    @Synchronized
    fun manifests(): List<PluginManifest> = entries.values.map { it.plugin.manifest }

    @Synchronized
    fun state(pluginId: String): PluginState? = entries[pluginId]?.state

    suspend fun enable(pluginId: String, context: PluginContext = PluginContext()): PluginLifecycleResult {
        val entry = synchronized(this) { entries[pluginId] }
            ?: return PluginLifecycleResult.Failed("Unknown plugin: $pluginId")
        return try {
            entry.plugin.onEnable(context)
            synchronized(this) { entry.state = PluginState.ENABLED }
            PluginLifecycleResult.Succeeded
        } catch (error: Exception) {
            synchronized(this) { entry.state = PluginState.FAILED }
            PluginLifecycleResult.Failed(error.message ?: "Plugin enable failed")
        }
    }

    suspend fun disable(pluginId: String): PluginLifecycleResult {
        val entry = synchronized(this) { entries[pluginId] }
            ?: return PluginLifecycleResult.Failed("Unknown plugin: $pluginId")
        return try {
            entry.plugin.onDisable()
            synchronized(this) { entry.state = PluginState.DISABLED }
            PluginLifecycleResult.Succeeded
        } catch (error: Exception) {
            synchronized(this) { entry.state = PluginState.FAILED }
            PluginLifecycleResult.Failed(error.message ?: "Plugin disable failed")
        }
    }

    suspend fun dispatch(request: PluginRequest): List<PluginInvocation> {
        val activePlugins = synchronized(this) {
            entries.values.filter { it.state == PluginState.ENABLED }.map { it.plugin }
        }
        return activePlugins.mapNotNull { plugin ->
            try {
                when (val response = plugin.handle(request)) {
                    PluginResponse.NotHandled -> null
                    else -> PluginInvocation(plugin.manifest.id, response)
                }
            } catch (error: Exception) {
                PluginInvocation(
                    plugin.manifest.id,
                    PluginResponse.Failed(error.message ?: "Plugin request failed")
                )
            }
        }
    }

    companion object {
        private val PLUGIN_ID = Regex("[a-z0-9]+([._-][a-z0-9]+)*")
    }
}
