# FR3K HUD

> Adaptive Android agent interface, capability router, device-control surface, automation hub, and communications bridge.

FR3K HUD is not another chatbot app. It's a **capability-aware coordination layer** that sits above normal Android, surfacing actions only when the supporting integration is actually present. The same UI works whether the underlying Hermes is on the LAN, the mesh is offline, GPS is denied, or Termux isn't installed — every unavailable action is simply hidden instead of failing silently.

The project follows the FR3K doctrine of progressive privilege: **Tier 0** works on every non-root Android, **Tier 1** adds user-granted capabilities (overlay, accessibility, location), **Tier 2–4** add Shizuku, rootless runtime adapters, and rooted Vector/libxposed.

---

## Quick start

```bash
# Build & sign a debug APK (Java 17 + Gradle wrapper + Android SDK 35)
./build.sh

# Run host-side unit tests
./test.sh

# Install on a connected emulator / device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch the dashboard
adb shell am start -n com.mcpintelligence.fr3k.hud/.ui.MainActivity
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

---

## Architecture

```
fr3k-hud/
├── app/                ← Activities, services, share receiver, HUD orb
├── core/               ← Capability registry, plugin manager, command framework, URL sanitiser
├── transport/          ← HttpsTransport, TransportHub, EnvelopeSigner
├── protocol/           ← Fr3kEnvelope, Capability, DeviceManifest, AgentAsk*, Fr3kLocation
├── ui/                 ← Fr3kTheme (dark cyberpunk), shared Composables
├── docs/               ← ARCHITECTURE, SECURITY, PROTOCOL, CAPABILITIES, etc.
├── schemas/            ← JSON schemas (device-manifest, envelope, agent.ask)
├── examples/           ← example configs, sample manifests
└── build.sh, test.sh
```

Read order:
1. [ARCHITECTURE.md](docs/ARCHITECTURE.md) — modules, lifecycle, data flow
2. [PROTOCOL.md](docs/PROTOCOL.md) — envelope, capability IDs, agent protocol
3. [CAPABILITIES.md](docs/CAPABILITIES.md) — capability registry semantics
4. [SECURITY.md](docs/SECURITY.md) — tier model, encryption, what FR3K will never do
5. [DEVICE_PAIRING.md](docs/DEVICE_PAIRING.md) — fleet discovery, signed manifests
6. [MESH.md](docs/MESH.md) — mesh transport abstraction (V2)
7. [TERMUX.md](docs/TERMUX.md) — Termux bridge (V2)
8. [HERMES.md](docs/HERMES.md) — Hermes AI bridge
9. [DEVELOPMENT.md](docs/DEVELOPMENT.md) — build, test, sign, debug
10. [TESTING.md](docs/TESTING.md) — test matrix, lifecycle coverage
11. [RELEASE.md](docs/RELEASE.md) — packaging, checksums

---

## V1 acceptance criteria status

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Display the FR3K floating HUD | ✅ | `HudOverlayService.kt` |
| 2 | Open the command palette | ✅ | `CommandPaletteActivity.kt` |
| 3 | Share text or URLs to FR3K | ✅ | `ShareReceiverActivity.kt` (registered as share target) |
| 4 | Use Ask About This | ✅ | `AskAboutThisActivity.kt` |
| 5 | Capture and send screenshot to AI | 🟡 | UI flow ready; MediaProjection capture wired in V2 |
| 6 | Communicate with Hermes | ✅ | `HermesProvider` + `HermesAskCommand` |
| 7 | Run approved Termux jobs | 🟡 | V2; Termux service stub reserved |
| 8 | Discover registered FR3K devices | ✅ | `DeviceRegistry` + HTTP transport |
| 9 | Send content to another FR3K device | ✅ | `OpenOnDeviceCommand` + `TransportHub` |
| 10 | Obtain and display GPS position | 🟡 | V2; location service slot reserved |
| 11 | Send via mesh adapter | 🟡 | V2; mesh service stub reserved |
| 12 | Display service status | ✅ | `MainActivity` capability + status panels |
| 13 | Continue basic operation without internet | ✅ | Hermes fallback + local-only paths |
| 14 | Continue core operation without Shizuku | ✅ | Shizuku not required for V1 |
| 15 | Continue core operation without root | ✅ | Zero root assumptions in code |
| 16 | Live integration panel (Termux / Shizuku / LSPatch / Morphe / Vector-root) | ✅ | `IntegrationsActivity` — every tier shows install/grant/state with one-tap smoke test |
| 17 | One-tap "GRANT ALL" runtime permissions | ✅ | `PermissionRegistry.runtimeNotGranted` + `IntegrationsActivity` top row |
| 18 | Per-feature permission registry (HUD/STT/GPS/BT/Storage/MicProjection/Notification/…) | ✅ | `PermissionRegistry.permissionsFor(Feature)` |

🟡 = wired architecturally, full implementation in V2 / V3.

---

## License

Internal — mcpintelligence.com.au.
## Floating HUD — Hitomi-style overlay family

FR3K HUD uses the same architectural pattern as `Decentricity/hitomi-android` — a single foreground service that owns multiple `WindowManager` overlay windows instead of a single Activity surface.

- **Orb** — 36 dp circular launcher at the top-left edge. Tap → quick HUD panel. Long-press → radial menu. Double-tap → command palette. Swipe up → fleet. Swipe down → screenshot. Drag → move. Drop on X → close. Drop off-screen → hide to edge-arc (tap arc to restore).
- **Chat bubble** — Clippy-style tail, drift input, send button. Routed to Hermes.
- **Mini-browser** — `WebView` overlay for URL-centric ask/share flows.
- **Terminal** — green-on-black `stdout` / `stderr` view, fed by the Termux bridge.
- **Edge arc + X-target** — visible only while the orb is being dragged; magnet on release.
- **Particle link** — visualises the connection between orb and the most recently activated overlay during drag.

### Driving overlays

The HUD service exposes a `BroadcastReceiver` so adb, automation, or extension packages can drive overlay state without owning the foreground service:

```bash
adb shell am broadcast -a com.mcpintelligence.fr3k.hud.OPEN_CHAT
adb shell am broadcast -a com.mcpintelligence.fr3k.hud.OPEN_TERMINAL
adb shell am broadcast -a com.mcpintelligence.fr3k.hud.OPEN_BROWSER --es url https://example.com
adb shell am broadcast -a com.mcpintelligence.fr3k.hud.STOP
```

## Integrations — five tiers, one panel

`IntegrationsActivity` (long-press the orb → radial → **INTEGRATIONS**) is the single surface that shows the live state of every partner FR3K HUD can talk to, with one-tap actions for each tier.

| Tier | Adapter | Install detection | Grant detection | Smoke test |
|------|---------|-------------------|------------------|------------|
| 1 | **Termux** (`TermuxBridge`) | `PackageManager` lookup on `com.termux` + `com.termux.api` | `RUN_COMMAND` permission + Termux:API presence | `echo hi-from-fr3k-<n>` via `am broadcast com.termux.RUN_COMMAND` |
| 2 | **Shizuku** (`ShizukuAdapter`) | `moe.shizuku.api` package | `Binder.pingBinder()` to the live Shizuku service | `pingBinder` |
| 3 | **LSPatch** (`LspatchAdapter`) | LSPosed manager package | `getInstalledApplications` for the FR3K announce filter | `announceToModules()` |
| 3 | **Morphe** (`MorphePatchRepository`) | JSON patch repo under `assets/morphe/` | SHA-256 verified on load | `loadAllAvailable()` |
| 4 | **Vector / root** (`VectorAdapter`) | `probeRoot()` over `/system/bin/su`, `/system/xbin/su`, `/sbin/su` | exec test `su -c id` | `runRootedShell("id")` |

### Auto-permission flow

The **GRANT ALL RUNTIME PERMISSIONS** row at the top of the panel walks the user through every Android runtime permission the app declares, in one tap. It uses the standard `ActivityCompat.requestPermissions` flow so the OS dialog appears for each pending permission; granting any one advances to the next.

`PermissionRegistry` keeps the canonical `Feature → List<String>` matrix in one place. To add a new permission later:

1. Add it to the feature's `permissionsFor(...)` case.
2. Add the string to `AndroidManifest.xml`.
3. The "GRANT ALL" path picks it up automatically — no UI change.

### Per-feature grant buttons

Each tier section has a per-tier button that targets just that tier's runtime permission (e.g. **GRANT STORAGE PERMISSION** under LSPatch, **GRANT MEDIA PERMISSIONS** under Morphe). Special non-runtime permissions (overlay, notification-listener, write-settings) open the matching `Settings.ACTION_*` screen via `SpecialPermissionLauncher`.
