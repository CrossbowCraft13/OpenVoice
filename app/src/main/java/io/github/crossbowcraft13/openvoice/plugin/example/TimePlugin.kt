package io.github.crossbowcraft13.openvoice.plugin.example

import io.github.crossbowcraft13.openvoice.plugin.OpenVoicePlugin
import io.github.crossbowcraft13.openvoice.plugin.PluginContext
import io.github.crossbowcraft13.openvoice.plugin.PluginManifest
import io.github.crossbowcraft13.openvoice.plugin.PluginPermission
import io.github.crossbowcraft13.openvoice.plugin.PluginRequest
import io.github.crossbowcraft13.openvoice.plugin.PluginResponse

/** A minimal local example that answers a deterministic `ping` command. */
class TimePlugin : OpenVoicePlugin {
    override val manifest = PluginManifest(
        id = "openvoice.example.time",
        name = "Example Time Plugin",
        version = "1.0.0",
        description = "Demonstrates a small offline plugin.",
        permissions = setOf(PluginPermission.READ_MEMORY)
    )

    private var enabled = false

    override suspend fun onEnable(context: PluginContext) {
        enabled = true
        context.log("${manifest.id} enabled")
    }

    override suspend fun onDisable() {
        enabled = false
    }

    override suspend fun handle(request: PluginRequest): PluginResponse {
        if (!enabled) return PluginResponse.Failed("Plugin is disabled")
        if (request.command != "ping") return PluginResponse.NotHandled
        return PluginResponse.Handled("pong", mapOf("plugin" to manifest.id))
    }
}
