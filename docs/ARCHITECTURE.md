# Architecture

FR3K HUD is a **layered, capability-aware Android application**. Each layer has explicit boundaries; failures are isolated; new capabilities attach as plugins.

## Layer diagram

```
┌──────────────────────────────────────────────────────────────┐
│ Android Activities (share / palette / ask / main)            │
│ ───────── Composable UI: Fr3kTheme (dark, monospace) ─────── │
├──────────────────────────────────────────────────────────────┤
│ HUD overlay service (HudOverlayService — Tier 1)              │
│ core service (Fr3kCoreService — Tier 0)                      │
│ mesh / location services (V2)                                │
├──────────────────────────────────────────────────────────────┤
│ Plugin Manager (failure isolation, owner-scoped teardown)     │
│   ├ Hermes plugin (Tier 0)                                   │
│   ├ Termux plugin (V2, Tier 0)                               │
│   ├ MeshCore / Meshtastic plugins (V2)                       │
│   └ Share / URL sanitiser / device handoff plugins            │
├──────────────────────────────────────────────────────────────┤
│ CapabilityRegistry · CommandRegistry · DeviceRegistry        │
│ AppSettings · SecureStore · AiProviderRegistry              │
│ Plugin contract (Fr3kPlugin) · Command contract (Fr3kCommand)│
├──────────────────────────────────────────────────────────────┤
│ Transport hub: Https (V1), WebSocket / BLE / Mesh / MQTT (V2)│
│ Envelope signer (NoOp in V1, Ed25519 in V3)                  │
├──────────────────────────────────────────────────────────────┤
│ Protocol: Fr3kEnvelope, Capability, DeviceManifest, Agent     │
│ JSON schemas, capability ids, agent profiles                 │
└──────────────────────────────────────────────────────────────┘
```

## Lifecycle

1. `Fr3kApplication.onCreate()` builds the process-wide `Fr3kCore` (lazy).
2. Plugins register; `PluginManager.startAll()` launches each in its own SupervisorJob.
3. Each plugin declares `capabilities()` and `commands()`. These flow into the
   registries. UI subscribes via `StateFlow`.
4. Activities subscribe to `capabilityRegistry.snapshot` and
   `commandRegistry.commandsFlow` — they recompose when the set changes.
5. Plugin failures are isolated; `unregisterAllByOwner(pluginId)` removes only
   that plugin's capabilities and commands.

## Capability model

A capability is an opaque identifier (`agent.ask`, `mesh.send`) plus a tier,
display name, description, and required permissions. Plugins register
capabilities at start. UI reads the current set; commands declare their
required capabilities and are filtered automatically.

```kotlin
class MyPlugin : Fr3kPlugin {
    override val pluginId = "fr3k.example"
    override fun capabilities() = listOf(
        Capability(
            id = "example.foo",
            displayName = "Example Foo",
            tier = CapabilityTier.TIER_0,
        )
    )
    override fun commands() = listOf(MyFooCommand())
}
```

## Command framework

Commands are first-class — every action (share, send, query, transform) is a
`Fr3kCommand`. The palette, share sheet, HUD, automation triggers, and
agent-suggested follow-ups all hit the same `CommandRegistry`.

```kotlin
class MyFooCommand : Fr3kCommand {
    override val id = "example.foo"
    override val title = "Foo"
    override val requiredCapabilities = setOf("example.foo")
    override val pluginId = "fr3k.example"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        if (!context.has("example.foo")) return CommandResult.Failed("missing capability")
        return CommandResult.Ok("did done")
    }
}
```

Commands never throw across plugin boundaries — failures are returned as
`CommandResult.Failed(reason, code)`.

## Transport

`Fr3kTransport` is a sealed abstraction. V1 ships HttpsTransport using
Android's `HttpURLConnection` (no third-party dependency). V2 adds
WebSocketTransport, BleTransport, MqttTransport. TransportHub tries each
registered transport in priority order, falls back on failure.

## Context firewall (§11)

`Fr3kContext` carries only what the user has explicitly supplied or what is
required for the current action. The Ask About This surface renders a
visible manifest of every field that would leave the device; each is toggleable.
The default profile is `NORMAL`. Profiles `LOCAL_ONLY` and `PRIVATE` further
strip fields before any network call.

## Plugin failure isolation

```kotlin
class PluginManager {
    fun start(pluginId: String) {
        reg.scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        reg.startJob = reg.scope.launch {
            runCatching {
                capabilityRegistry.registerAll(pluginId, plugin.capabilities())
                plugin.commands().forEach { commandRegistry.register(it) }
                plugin.start()
            }.onFailure {
                capabilityRegistry.unregisterAllByOwner(pluginId)
                commandRegistry.unregisterByPlugin(pluginId)
            }
        }
    }
}
```

A misbehaving Meshtastic plugin cannot affect Hermes.

## Threading

All long-lived work uses coroutines. UI subscribes to `StateFlow`s; mutations
trigger recomposition. Services use lifecycle-aware components. The
`HudOverlayService` is foreground with `specialUse` type (Android 14+ compliant).

## Permissions model

Declared in `AndroidManifest.xml`, used only when needed, never escalated
automatically. The HUD overlay only starts when `Settings.canDrawOverlays`
returns true; otherwise it logs and stops.

## What lives where (V1)

| Module | Owns | Notes |
|--------|------|-------|
| `protocol` | Envelope, capability, agent, manifest data classes | No Android deps |
| `transport` | HTTPS transport, transport hub, signer | Pure Kotlin / stdlib |
| `core` | Registries, command framework, plugin manager, identity, secure store, URL sanitiser | Android-aware |
| `ui` | Theme + shared Compose components | Compose only |
| `app` | Activities, services, share target, HUD orb | Full Android app |