package com.example.openvoice.plugin.example

import com.example.openvoice.plugin.OpenVoicePlugin
import com.example.openvoice.plugin.PluginContext
import com.example.openvoice.plugin.PluginManifest
import com.example.openvoice.plugin.PluginPermission
import com.example.openvoice.plugin.PluginRequest
import com.example.openvoice.plugin.PluginResponse

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
