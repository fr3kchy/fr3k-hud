# Testing

## Host-side (JVM unit tests)

Covered by `test.sh`:

- `core:UrlSanitiserTest` — tracking-param stripping, domain rules.
- `core:CapabilityRegistryTest` — register / unregister, tier filtering,
  missing-for queries.
- `core:CommandRegistryTest` — fuzzy search, availability filtering,
  per-plugin teardown.
- `protocol:Fr3kEnvelopeTest` — serialization round-trip, factory output.

## Target-side (instrumentation tests)

V1 stubs:

- Capability smoke test
- Activity launch smoke test
- Share target invocation smoke test

## Lifecycle states (planned)

- Cold start → dashboard renders
- Background → process kill → cold restart restores identity
- Screen off → screen on → HUD overlay still present
- Rotation → state retained
- Multi-window → single-process still works
- Battery saver → foreground service notification still posts
- Doze → wake → no crashes
- Permission revoked → dependent commands hidden immediately

## States matrix

| State | Expected behaviour |
|-------|--------------------|
| No root | App works fully; Vector adapter absent |
| Shizuku disabled | App works fully; advanced caps hidden |
| Shizuku enabled | Advanced caps surface |
| Overlay disabled | HudOverlayService refuses to start |
| Overlay enabled | Orb appears, tap → palette |
| Accessibility disabled | No app-specific context adaptors |
| Accessibility enabled | V3 Vector adapters active |
| Offline | Local commands still work; Hermes falls back |
| Wi-Fi only | LAN devices reachable |
| Mobile only | LAN devices may be unreachable; mesh still works |
| Bluetooth on | MeshCore / BLE adapters reachable |
| MeshCore connected | `meshcore.send` exposed |
| Meshtastic connected | `meshtastic.send` exposed |
| Hermes online | Full AI capability |
| Hermes offline | Local fallback; AI capability remains exposed but returns offline messages |
| Termux installed | `termux.job` exposed (V2) |
| Termux absent | `termux.job` not exposed; no error |

## Performance targets

| Metric | Target |
|--------|--------|
| Idle CPU | < 0.5% |
| Idle RAM | < 80 MB |
| Wakeups / hr | < 12 |
| Battery drain | < 1% / hr |
| Cold start to dashboard | < 800 ms |
| HUD tap → palette visible | < 250 ms |
| Command latency | < 200 ms (local), < 2 s (Hermes LAN) |

## Compatibility matrix

- Android 12 (API 31) — minimum supported
- Android 13 (API 33) — verified
- Android 14 (API 34) — primary test target
- Android 15 (API 35) — primary test target (compileSdk)
- Android 16 (API 36) — future compile target