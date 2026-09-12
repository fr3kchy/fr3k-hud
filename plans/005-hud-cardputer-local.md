# Plan 004: Add direct HUD–Cardputer local pairing and handoff

## Status
- Priority: P2; Effort: M; Risk: MED; Depends on: 002, 003; Category: integration; Planned at: `2f7fb05b`, 2026-09-07

## Why this matters
HUD and Cardputer should add useful context exchange even when BLACKWAVE is absent. Local pairing must never impersonate fleet enrollment or authorize radio, OTA, or actuation.

## Current anchors
HUD has `DeviceRegistry.kt`, `DeviceHandoffAdapter.kt`, `Fr3kEnvelope.kt` and `DeviceIdentity.kt`. Cardputer has local main/render/input and event/save code but no authenticated ecosystem adapter; LoRa is unavailable. Use immutable local device identity, model metadata, transport address, and optional fleet enrollment as separate fields.

## Steps
1. Define a small `fr3k-local-peer/1` envelope adapter with identity fingerprint, nonce, capability list, max payload, expiry and correlation; allow context, notification, safe read and save-sync proposals only. Expected: cross-language golden vectors and negative auth/replay/size tests pass.
2. Add explicit QR/button pairing and unpair/revoke on both sides. Expected: LAN/BLE presence, alias, IP or USB alone cannot establish trust; restart reconnects only with stored approved trust.
3. Implement bounded HUD handoff and Cardputer receiver with deduplication, cancellation, disconnect and local persistence. Expected: a disconnected Cardputer leaves HUD core unaffected and duplicate events execute once.

## Done criteria
HUD+Cardputer works without BLACKWAVE; no fleet grant or risky command crosses the local adapter; simulated fixture acceptance is recorded separately from physical evidence.

## STOP conditions
Stop if direct pairing would require fleet keys, OTA signer access, radio transmit authority, or unverified hardware behavior.
