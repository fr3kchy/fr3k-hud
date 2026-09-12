# Plan 006: Ship optional HUD BLACKWAVE mode and full-system resilience

## Status
- Priority: P1; Effort: L; Risk: HIGH; Depends on: 003, 004, 005, 006; Category: architecture/integration; Planned at: `2f7fb05b`, 2026-09-07

## Why this matters
The richest experience should be additive and understandable: users choose BLACKWAVE mode, then pairing and grants determine actions. Disabling it must preserve ordinary HUD, Cardputer and BLACKWAVE operation.

## Steps
1. Add an explicit persisted HUD mode flag and lifecycle-owned session manager. Do not poll or expose fleet capabilities while disabled; cancel jobs and retain safe cached views when disconnected. Expected: cold start, rotation, Doze, process death and disable tests pass with no gateway.
2. Project only authenticated, unexpired role capabilities into one canonical HUD registry. Map denials to visible `UNAUTHORIZED`, `FORBIDDEN`, `CAPABILITY_MISSING`, `POLICY_DENIED`, `OFFLINE`, `BLOCKED`, `UNSUPPORTED`; AI proposals use the same confirmation path. Expected: capability changes after expiry/revocation are immediate and no command reports success without a confirmed response.
3. Consolidate navigation into progressive groups: BLACKWAVE Fleet, LoRa & Mesh, AI & Hermes, Capture & Context, Automations, Android Integrations, System & Diagnostics. Expected: empty groups hide or show a clear unavailable state; all nested actions remain searchable/accessibility-labelled.
4. Add redacted health/support export with app/gateway/contract versions, session age, last sync, clock and transport state. Expected: tests reject tokens, private keys, cookies, passwords and unapproved location data.
5. Run the complete fixture flow: clean start, pairing, catalog, status, safe read, denial, blocked physical operation, event, offline, reconnect, revoke, re-pair, restart. Expected: all three products remain independently operable and combined flows have one owner per side effect.

## Done criteria
All seven configuration rows in `plans/README.md` have executable acceptance evidence; simulator and physical records are separate; API incompatibility is explicit; no duplicate registry, command execution, or authority exists.

## STOP conditions
Stop if mode enable grants authority, offline queue can execute unsafe work, a physical operation is required for a host claim, or any repository’s independent startup regresses.
