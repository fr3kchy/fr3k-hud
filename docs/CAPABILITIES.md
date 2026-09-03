# Capabilities

FR3K HUD's capability registry is the single source of truth for what the app
can currently do. The UI never displays an action that requires a capability
that isn't currently registered.

## Anatomy

```kotlin
data class Capability(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val tier: CapabilityTier = CapabilityTier.TIER_0,
    val requiredPermissions: List<String> = emptyList(),
    val experimental: Boolean = false,
    val version: Int = 1,
)
```

## Tiers

- `TIER_0` — Always-on, no special permission.
- `TIER_1` — User-granted runtime permission (overlay, location, etc.).
- `TIER_2` — Shizuku running + granted.
- `TIER_3` — Rootless LSPatch/NPatch-compatible framework installed.
- `TIER_4` — Rooted Vector/libxposed enabled.

Capabilities advertise their tier; UI may hide higher-tier capabilities behind a
setup hint until the user has installed the prerequisite.

## Registration

```kotlin
class MyPlugin : Fr3kPlugin {
    override fun capabilities(): List<Capability> = listOf(
        Capability(
            id = "example.foo",
            displayName = "Example Foo",
            tier = CapabilityTier.TIER_0,
        ),
    )
}
```

The plugin manager calls `registerAll(pluginId, capabilities())` at start.
At stop, it calls `unregisterAllByOwner(pluginId)`.

## Reading

```kotlin
val available = capabilityRegistry.snapshot.value.keys
commandRegistry.search(query, available)
```

## Truthfulness

- The registry, not the UI, is the source of truth.
- Plugins must register capabilities when they're actually ready, not at start.
- When a plugin loses its backing (e.g. Hermes endpoint unreachable for >5 min),
  it may voluntarily unregister its `agent.ask` capability — the palette
  instantly hides the dependent command.

## Adding a new capability

1. Add the id to `Capabilities` object in `protocol/.../Capability.kt`.
2. Add the tier + display name.
3. Implement the plugin that registers it.
4. Write tests for the command that requires it.

That's it. The registry, palette, share sheet, and HUD all pick it up.