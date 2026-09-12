# Cardputer assessment (working tree, 2026-09-07)

Baseline: branch `feature/demon-signal-v4` at `27eb704083cba8ef15ac5f975b759c2b2839fb20`; renderer/game/assets changes and generated outputs are present. Inventory and command log are in `inventory.txt`, `checks.json`, and the per-command logs.

| Subsystem | Static | Verification | Assessment |
|---|---|---|---|
| boot/main loop/render/input | reviewed `src/main.cpp`, renderer and modes | five native targets pass; safe firmware build pass | local experience is viable; new systems need caller audit |
| games/actors/combat/events/saves | reviewed tracked and untracked systems | native save/fruit/combat/event targets pass | several systems are test-covered but not production-wired |
| assets/generated atlas | reviewed source/compiler manifests | `asset_compiler.py --check` pass | generated outputs not independent review |
| GNSS/telemetry/files/USB | reviewed services and storage | host tests pass | USB/SD write ownership and GPS underflow are defects |
| networking/WiGLE/WPA/LoRa | reviewed network paths | no RF/hardware; LoRa adapter unavailable | TLS verification and missing adapter block ecosystem claims |
| build variants/partitions/update/recovery | inventoried PlatformIO and scripts | `m5cardputer-safe` pass; no physical recovery | physical/update readiness blocked |

37 host tests pass with three Pillow deprecation warnings; five native targets pass; assets pass; `scripts/verify_fr3k.py` fails stale “eye/Palette” assertions after atlas cutover. Checked findings F-07–F-12 are in the root index.
