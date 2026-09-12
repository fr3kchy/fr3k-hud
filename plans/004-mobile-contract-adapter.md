# Plan 003: Freeze and adapt the BLACKWAVE mobile contract

## Status
- Priority: P1; Effort: L; Risk: HIGH; Depends on: 002; Category: integration/security; Planned at: `2f7fb05b`, 2026-09-07

## Why this matters
The existing mobile contract is close but not compatible: HUD calls `/mobile/v1/ping` and `/mobile/v1/device/{id}` with `X-Client-Id`, while BLACKWAVE requires authenticated `X-Blackwave-Client`, exposes `/health` and `/devices/{model_id}`, and returns explicit cached/live state.

## Scope
BLACKWAVE `src/blackwave_fleet/mobile.py`, `pairing.py`, models/schemas/tests and integration fixtures; HUD `core/.../BlackwaveBridgeClient.kt`, role/plugin, protocol and transport tests. Preserve `blackwave-device/2`, `fr3k-blackwave-peer/1`, FR3KCFG2 and ADR-020. Do not create a second registry, signer, OTA policy or fleet identity.

## Steps and verification
1. Write a field-by-field `fr3k-blackwave-mobile/1` contract and golden fixtures for health, role, catalog, fleet, device, evidence, capability, error, pairing, expiry and revocation. Validate malformed, versionless, oversized, stale and unknown-field cases in Python and JVM. Expected: valid vectors pass in both repos; invalid vectors fail closed.
2. Add a BLACKWAVE adapter with compatibility aliases only where existing clients require them; canonical routes and header are documented. Require bounded bodies, correlation/freshness, authorization and safe idempotent retry rules. Expected: route/header contract tests prove both canonical and intentional legacy behavior.
3. Replace HUD raw `HttpURLConnection` calls with typed cancellable client state: `LIVE`, `CACHED`, `OFFLINE`, `UNAUTHORIZED`, `REVOKED`, `BLOCKED`, `UNSUPPORTED`, `SERVER_ERROR`; validate HTTPS/SPKI and response size. Expected: timeout, cancellation, malformed response, wrong certificate, expiry and denial tests pass.
4. Keep pairing operator-confirmed and fingerprint-bound; store only secure session material. Expected: changed key, replay, expiry, revoke, unpair and re-pair tests fail closed.

## Done criteria
No route/header ambiguity remains; shared fixtures validate in both repositories; BLACKWAVE remains final authority; no side-effect is automatically retried or queued.

## STOP conditions
Stop if compatibility requires moving signing/OTA/revocation/evidence authority to Android or breaking an existing contract without an adapter.
