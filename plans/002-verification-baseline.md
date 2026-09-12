# Plan 001: Establish reproducible three-repository verification gates

> Execute read-only checks in disposable copies when a command writes build, generated, or release output. Do not flash, transmit RF, provision, deploy, or change credentials.

## Status
- Priority: P1; Effort: M; Risk: LOW; Depends on: none; Category: tests/DX; Planned at: `2f7fb05b`, 2026-09-07

## Why this matters
The three working trees contain active source and generated changes, but the existing aggregate runners omit important targets. A shared ledger is required before refactors or integration claims can be trusted.

## Current state and commands
- HUD: `./gradlew test`, `./gradlew lint`, `./gradlew assembleDebug`; current disposable run is in `plans/ecosystem-evidence/hud/gradle-*.log` and has a `:core:compileDebugUnitTestKotlin` failure plus lint failure.
- BLACKWAVE: `uv run pytest -q`; `python3 integration/validate_contract.py`; fleet-console `pnpm build`; WebUI test command from each package; results are in `blackwave/*.log` (135 pytest, contract 9 devices, WebUI 40 tests).
- Cardputer: `python3 -m pytest -q tests/host`; each native target under `tests/native`; `python3 tools/asset_compiler.py --check`; `pio run -e m5cardputer-safe`; results are in `cardputer/*.log` (37 host, five native, assets and safe build pass; verifier fails stale assertions).

## Steps and gates
1. Re-run each command in an isolated copy of the current tree after inspecting scripts for writes. Record exact SHA, tool versions, exit code, and artifact path in `plans/ecosystem-evidence/<repo>/coverage.md`. Expected: no unlabelled pass/fail.
2. Add omitted module/target commands to the documented runners without changing product behavior. Expected: every first-party test/build target has a named command or `BLOCKED` reason.
3. Reconcile generated, vendored, caches, binaries, historical research, and release evidence using the lead inventories. Expected: no generated artifact is described as independently source-reviewed.

## Done criteria
- [ ] `coverage.md` exists for all three repositories with every subsystem status.
- [ ] Commands above are reproducible or explicitly `BLOCKED`.
- [ ] No source or user working tree outside disposable copies changed.

## STOP conditions
Stop if a command requires live hardware, RF, credentials, deployment, or source mutation outside a disposable copy.

## Maintenance
Update this ledger whenever a module, device target, runner, or contract is added; a green aggregate command is insufficient if a target is omitted.
