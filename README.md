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

🟡 = wired architecturally, full implementation in V2 / V3.

---

## License

Internal — mcpintelligence.com.au.