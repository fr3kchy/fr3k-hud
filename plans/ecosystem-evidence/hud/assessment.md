# HUD assessment (working tree, 2026-09-07)

Baseline: `master` at `2f7fb05b1866f16e7c8a0d79f0ec923b1cfda415`; modified `Fr3kChatBubble.kt`, untracked docs/plans/review. Inventory: 180 nonignored files; generated artifacts and release media are classified in `lead/fr3k-hud-files.json`.

| Subsystem | Static | Test/build | Assessment |
|---|---|---|---|
| Android app/startup/lifecycle/permissions | reviewed manifest, activities, services, receivers | Gradle continuation reached lint; failure recorded | startup and optional-service isolation need characterization |
| overlays/browser/chat/clipboard/share/context | reviewed all first-party overlay and context classes | app tests not all reached | external URL/receiver boundary is HIGH |
| core plugins/commands/automation/providers | reviewed registries, adapters, Hermes/OpenCode | core unit compile fails from app-only import | local profile and result semantics need repair |
| protocol/transport/pairing/registry/storage | reviewed typed envelope, HTTPS, blackwave client, secure store | protocol tests available; transport integration absent | HUD client is partial and contract-incompatible |
| docs/schemas/release/scripts | reviewed | `test.sh` omission documented in existing audit | physical/emulator evidence blocked |

Evidence: `gradle-verification.log` shows `:core:compileDebugUnitTestKotlin` failure; `gradle-continue.log` shows lint failure. Checked findings F-01–F-04 are in the root index. No device or live network tests were performed.
