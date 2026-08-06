# Plugin Development

## Current scope

OpenVoice provides a small, in-process plugin SDK for local extensions. Plugins are registered by the host application; this milestone intentionally does not load arbitrary code, install packages, or connect to a marketplace.

The public contract lives in `com.example.openvoice.plugin`:

- `OpenVoicePlugin` — plugin lifecycle and request handler.
- `PluginManifest` — stable ID, version, description, and declared permissions.
- `PluginRegistry` — registration, enable/disable lifecycle, and dispatch.
- `PluginRequest` / `PluginResponse` — deterministic command exchange.

## Implementing a plugin

```kotlin
class GreetingPlugin : OpenVoicePlugin {
    override val manifest = PluginManifest(
        id = "example.greeting",
        name = "Greeting",
        version = "1.0.0",
        description = "Replies to a greeting"
    )

    private var enabled = false

    override suspend fun onEnable(context: PluginContext) {
        enabled = true
    }

    override suspend fun onDisable() {
        enabled = false
    }

    override suspend fun handle(request: PluginRequest): PluginResponse {
        if (!enabled) return PluginResponse.Failed("Plugin is disabled")
        if (request.command != "hello") return PluginResponse.NotHandled
        return PluginResponse.Handled("Hello from OpenVoice")
    }
}
```

Plugin IDs must be lowercase and use only letters, numbers, dots, hyphens, and underscores. IDs are unique within a registry. Registration rejects duplicate IDs, malformed IDs, and manifests without a name or version.

## Lifecycle and dispatch

1. Register the plugin. It starts in `REGISTERED` state.
2. Call `enable(id)` before dispatching requests. A successful enable moves it to `ENABLED`.
3. The registry dispatches only to enabled plugins.
4. `NotHandled` responses are omitted from the returned invocations, while handled and failed responses retain the plugin ID.
5. Calling `disable(id)` prevents future dispatch and moves the plugin to `DISABLED`.
6. Exceptions from lifecycle callbacks are contained and move the plugin to `FAILED`; cancellation is rethrown and restores the prior state. Exceptions during request handling become `PluginResponse.Failed` so one plugin cannot abort the dispatch cycle.

Lifecycle operations for a plugin are serialized, and a plugin cannot be unregistered while it is enabled, transitioning, or handling a request. The example `TimePlugin` responds to `ping` with `pong` and demonstrates the smallest useful implementation.

## Permissions and safety

Permissions are descriptive metadata in this first milestone. The host should enforce permission checks before exposing sensitive capabilities such as screen, action, or memory access. Plugins must remain local-first and should not introduce network or telemetry dependencies.

The registry is deliberately explicit and in-process. Marketplace discovery, package signing, sandboxing, persistence, and third-party skill distribution remain future roadmap work.

## Testing

Plugin business logic belongs in JVM tests under `app/src/test`. At minimum, test registration validation, duplicate handling, lifecycle transitions, cancellation rollback, concurrent lifecycle calls, dispatch/unregister coordination, stable manifests, unknown commands, disabled behavior, and exception isolation. The reference tests are in `PluginRegistryTest.kt`.
