# Mesh -> BLACKWAVE

V1 shipped a generic `MeshPlugin` with stub adapters for MeshCore, Meshtastic, and Reticulum. **This has been replaced by `BlackwavePlugin`** - BLACKWAVE is the device authority; HUD is a capability-aware surface that consumes it.

## Why the change

The generic mesh abstraction duplicated work BLACKWAVE already does:

- Device identity and provisioning (BLACKWAVE owns this)
- Signed commands and revocation (BLACKWAVE's `fr3k-blackwave-peer/1` protocol)
- Safety constraints and risk ceilings (BLACKWAVE's risk lattice)
- Fleet status and discovery (BLACKWAVE's fleet bridge)

Having two independent mesh layers meant two places to maintain authority logic, two places to update when devices changed, and no single source of truth for "what can this device do?"

## Architecture

```
fr3k-hud (Android)
  BlackwavePlugin
    |- connects to blackwave fleet bridge (LAN/HTTPS)
    |- fetches role manifest on connect
    |- maps role scopes -> capabilities (blackwave.*)
    |- registers fleet commands
    |- polls for role updates (30s)
          |
          v
blackwave fleet bridge (Raspberry Pi 500)
  |- fr3k-blackwave-peer/1 protocol
  |- device provisioning + signed commands
  |- approval-required transmit gate
  |- revocation + audit
  |- role manifest endpoint (/mobile/v1/role)
```

## Role manifest

The fleet bridge serves a **role manifest** that defines what the connected identity can do. HUD reads this and derives its capability set from it:

```json
{
  "role_id": "bw:role:fr3k-owner",
  "trust_tier": "owner",
  "allowed_scopes": ["fleet.discover", "radio.status", ...],
  "capabilities_map": {
    "fleet.discover": "blackwave.fleet.discover",
    "radio.status": "blackwave.radio.status"
  }
}
```

The `capabilities_map` translates fleet scopes into HUD capability IDs. HUD never has to understand CBOR, signing, or radio config - the bridge handles it all.

## Capability mapping

| Fleet scope | HUD capability ID | Notes |
|---|---|---|
| `fleet.discover` | `blackwave.fleet.discover` | Always registered |
| `profile.apply` | `blackwave.profile.apply` | Requires approval |
| `ota.apply` | `blackwave.ota.apply` | Requires approval |
| `radio.status` | `blackwave.radio.status` | Read-only |
| `radio.configure` | `blackwave.radio.configure` | Requires approval |
| `reticulum.status` | `blackwave.reticulum.status` | Read-only |
| `reticulum.link_test` | `blackwave.reticulum.link_test` | Read-only |
| `epaper.status` | `blackwave.epaper.status` | Read-only |
| `battery.telemetry` | `blackwave.battery.telemetry` | Read-only |
| `location.read` | `blackwave.location.read` | Owner-only |
| `device.reboot` | `blackwave.device.reboot` | Requires approval |
| `device.describe` | `blackwave.device.describe` | Read-only |

## Feature detection

When the bridge isn't reachable, `BlackwavePlugin` registers only the base `blackwave.fleet.discover` capability. The command palette hides commands whose required capabilities aren't registered. No bridge -> no blackwave buttons, same pattern as the old mesh approach.

## Radio configuration

FR3K HUD does **not** configure radio frequencies, region, or hardware. BLACKWAVE handles all radio policy. The user's role manifest determines what radio operations are available.

## Offline behaviour

When the internet disappears, BLACKWAVE devices continue to operate autonomously per their enrolled policy. HUD loses its bridge connection and hides blackwave capabilities until the bridge is reachable again.