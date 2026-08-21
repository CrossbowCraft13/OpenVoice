package io.github.crossbowcraft13.openvoice.plugin

/**
 * Stable metadata exposed by a plugin to the host application.
 *
 * Plugin IDs are the durable identity used for registration and dispatch. They should be
 * lowercase and may contain letters, numbers, dots, hyphens, and underscores.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val permissions: Set<PluginPermission> = emptySet()
)

enum class PluginPermission {
    READ_SCREEN,
    PERFORM_ACTIONS,
    READ_MEMORY,
    WRITE_MEMORY
}

data class PluginRequest(
    val command: String,
    val arguments: Map<String, String> = emptyMap()
)

data class PluginContext(
    val attributes: Map<String, String> = emptyMap(),
    val log: (String) -> Unit = {}
)

/** A result returned by a plugin for one request. */
sealed interface PluginResponse {
    data class Handled(
        val message: String,
        val data: Map<String, String> = emptyMap()
    ) : PluginResponse

    data object NotHandled : PluginResponse

    data class Failed(val reason: String) : PluginResponse
}

enum class PluginState {
    REGISTERED,
    ENABLED,
    DISABLED,
    FAILED
}

interface OpenVoicePlugin {
    val manifest: PluginManifest

    suspend fun onEnable(context: PluginContext)

    suspend fun onDisable()

    suspend fun handle(request: PluginRequest): PluginResponse
}

sealed interface PluginRegistrationResult {
    data object Registered : PluginRegistrationResult

    data class Rejected(val reason: String) : PluginRegistrationResult
}

sealed interface PluginLifecycleResult {
    data object Succeeded : PluginLifecycleResult

    data class Failed(val reason: String) : PluginLifecycleResult
}

data class PluginInvocation(
    val pluginId: String,
    val response: PluginResponse
)
